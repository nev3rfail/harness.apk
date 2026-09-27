#!/usr/bin/env python3
"""P3's host tests: the waiter's status, forwarding, and the death of either
process.

Usage: p3.py PROOT CATCH INTERP TMPDIR

PROOT is libproot.so, CATCH is catch.c built, INTERP is the dynamic linker
that runs PROOT, and TMPDIR is an empty directory. Prints "ok   NAME" or
"FAIL NAME: why" for each test and exits 1 when one fails. Each test's
processes are `sleep 12NN` with its own NN, and each test ends by killing
the ones it started.
"""
import os
import pty
import re
import select
import signal
import subprocess
import sys
import time

P, CATCH, INTERP, TMP = sys.argv[1:5]
TESTS = []


def test(name):
    def register(function):
        TESTS.append((name, function))
        return function
    return register


def stat(pid):
    """(state, ppid) of @pid, or None when it is gone."""
    try:
        with open("/proc/%d/stat" % pid) as f:
            fields = f.read().rsplit(")", 1)[1].split()
        return fields[0], int(fields[1])
    except (OSError, IndexError):
        return None


def alive(pid):
    s = stat(pid)
    return s is not None and s[0] not in "ZX"


def processes():
    for name in os.listdir("/proc"):
        if name.isdigit():
            yield int(name)


def children(pid):
    return [p for p in processes() if (stat(p) or ("", 0))[1] == pid and alive(p)]


def cmdline(pid):
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            return f.read().split(b"\0")[:-1]
    except OSError:
        return []


def sleeps(*seconds):
    """Live `sleep N` processes, for each N in @seconds."""
    want = [[b"sleep", str(s).encode()] for s in seconds]
    return [p for p in processes() if cmdline(p) in want and alive(p)]


def until(predicate, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = predicate()
        if value:
            return value
        time.sleep(0.02)
    return predicate()


def kill(pids, sig=signal.SIGKILL):
    for p in pids:
        try:
            os.kill(p, sig)
        except OSError:
            pass


def start(args, **kw):
    kw.setdefault("stdin", subprocess.DEVNULL)
    return subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kw)


def ready(proc):
    """The pid and ppid catch prints; its ppid is the tracer."""
    line = proc.stdout.readline().decode()
    m = re.match(r"ready pid=(\d+) ppid=(\d+)", line)
    return (int(m.group(1)), int(m.group(2))) if m else (None, None)


def lines(proc, n):
    return [proc.stdout.readline().decode().strip() for _ in range(n)]


def child_of(pid):
    kids = until(lambda: children(pid)) if pid else None
    return kids[0] if kids else None


def daemon_run(command):
    """Run `sh -c @command` under proot, which prints $PPID, the tracer."""
    r = subprocess.run([P, "sh", "-c", command], capture_output=True, timeout=10)
    return int(r.stdout)


# --- status ---

@test("vpid 1's exit status")
def _():
    rc = subprocess.run([P, "sh", "-c", "exit 7"], timeout=10).returncode
    return rc == 7, "rc=%d" % rc


@test("vpid 1's death by signal")
def _():
    rc = subprocess.run([P, "sh", "-c", "kill -TERM $$"], capture_output=True, timeout=10).returncode
    return rc == -signal.SIGTERM, "rc=%d" % rc


@test("the tracer's status after a usage error")
def _():
    r = subprocess.run([P, "--no-such-option"], capture_output=True, timeout=10)
    return r.returncode == 1 and b"unknown option" in r.stderr, "rc=%d %r" % (r.returncode, r.stderr)


# --- stdio and daemons ---

@test("$(proot ...) returns while a daemon that closed its stdio runs")
def _():
    t = time.monotonic()
    try:
        r = subprocess.run([P, "sh", "-c", "setsid sleep 1201 </dev/null >/dev/null 2>&1 & echo started"],
                           capture_output=True, timeout=10)
        out = r.stdout
    except subprocess.TimeoutExpired:
        out = b"timeout"
    daemon = until(lambda: sleeps(1201), 2)
    kill(sleeps(1201))
    return out == b"started\n" and time.monotonic() - t < 5 and daemon, "out=%r daemon=%r" % (out, daemon)


# --- forwarding ---

@test("kill to the waiter reaches vpid 1 through the tracer")
def _():
    proc = start([P, CATCH, "1"])
    pid, tracer = ready(proc)
    os.kill(proc.pid, signal.SIGUSR1)
    got = lines(proc, 1)
    rc = proc.wait(5)
    return got == ["sig=10 code=0"] and rc == 1 and tracer != proc.pid, \
        "%r rc=%d tracer=%r waiter=%d" % (got, rc, tracer, proc.pid)


@test("kill -USR1 and kill -SEGV to the waiter reach vpid 1")
def _():
    proc = start([P, CATCH, "2"])
    ready(proc)
    os.kill(proc.pid, signal.SIGUSR1)
    time.sleep(0.2)
    os.kill(proc.pid, signal.SIGSEGV)
    got = lines(proc, 2)
    rc = proc.wait(5)
    return got == ["sig=10 code=0", "sig=11 code=0"] and rc == 2, "%r rc=%d" % (got, rc)


@test("the tracer drops a kill from another process")
def _():
    proc = start([P, CATCH, "1"])
    pid, tracer = ready(proc)
    os.kill(tracer, signal.SIGUSR2)
    time.sleep(0.2)
    os.kill(proc.pid, signal.SIGUSR1)
    got = lines(proc, 1)
    rc = proc.wait(5)
    return got == ["sig=10 code=0"] and rc == 1, "%r rc=%d" % (got, rc)


@test("a killpg reaches vpid 1 twice")
def _():
    # A realtime signal, so the two copies queue.
    rt = signal.SIGRTMIN + 1
    proc = start([P, CATCH, "2"], start_new_session=True)
    ready(proc)
    os.killpg(proc.pid, rt)
    got = lines(proc, 2)
    rc = proc.wait(5)
    return got == ["sig=%d code=0" % rt] * 2 and rc == 2, "%r rc=%d" % (got, rc)


# --- death of either process ---

@test("SIGKILL of the waiter takes every tracee")
def _():
    proc = start([P, "sh", "-c", "sleep 1202 & sleep 1203"])
    tracees = until(lambda: len(sleeps(1202, 1203)) == 2 and sleeps(1202, 1203))
    os.kill(proc.pid, signal.SIGKILL)
    proc.wait(5)
    gone = until(lambda: not sleeps(1202, 1203))
    kill(sleeps(1202, 1203))
    return tracees and gone, "tracees=%r" % tracees


@test("SIGKILL of the tracer takes every tracee and the waiter")
def _():
    proc = start([P, "sh", "-c", "sleep 1204 & sleep 1205"])
    tracees = until(lambda: len(sleeps(1204, 1205)) == 2 and sleeps(1204, 1205))
    os.kill(child_of(proc.pid), signal.SIGKILL)
    rc = proc.wait(5)
    gone = until(lambda: not sleeps(1204, 1205))
    kill(sleeps(1204, 1205))
    return tracees and gone and rc == -signal.SIGKILL, "tracees=%r rc=%d" % (tracees, rc)


@test("SIGKILL of an inner tracer under a nested proot takes its tracees")
def _():
    # The inner proot's waiter is the outer tracer's child.
    proc = start([P, P, "sh", "-c", "sleep 1206"])
    sleeper = until(lambda: sleeps(1206))
    inner_tracer = child_of(child_of(child_of(proc.pid)))
    if inner_tracer:
        os.kill(inner_tracer, signal.SIGKILL)
    gone = until(lambda: not sleeps(1206))
    kill(sleeps(1206))
    proc.kill()
    proc.wait(5)
    return sleeper and inner_tracer and gone, "sleeper=%r inner tracer=%r" % (sleeper, inner_tracer)


@test("EXITKILL: SIGKILL of the tracer after the waiter exited takes a daemon")
def _():
    tracer = daemon_run("setsid sleep 1207 </dev/null >/dev/null 2>&1 & echo $PPID")
    daemon = until(lambda: sleeps(1207))
    os.kill(tracer, signal.SIGKILL)
    gone = until(lambda: not sleeps(1207))
    kill(sleeps(1207))
    return daemon and gone, "daemon=%r" % daemon


# --- SIGQUIT ---

@test("SIGQUIT from the terminal ignored, and from kill forwarded")
def _():
    child, master = pty.fork()
    if child == 0:
        os.execv(P, [P, CATCH, "2"])
    buf = [b""]

    def read_until(pattern, timeout=5.0):
        end = time.monotonic() + timeout
        while not re.search(pattern, buf[0]) and time.monotonic() < end:
            r, _, _ = select.select([master], [], [], 0.1)
            if r:
                try:
                    buf[0] += os.read(master, 4096)
                except OSError:
                    break
        return re.search(pattern, buf[0])

    read_until(rb"ready")
    os.write(master, b"\x1c")
    first = read_until(rb"sig=3 code=128")
    time.sleep(0.5)
    before_kill = len(re.findall(rb"sig=", buf[0]))
    os.kill(child, signal.SIGQUIT)
    second = read_until(rb"sig=3 code=0")
    _, status = os.waitpid(child, 0)
    os.close(master)
    return first and before_kill == 1 and second and os.WIFEXITED(status) \
        and os.WEXITSTATUS(status) == 2, "%r status=%r" % (buf[0], status)


@test("SIGQUIT after vpid 1's reap takes its default action")
def _():
    tracer = daemon_run("setsid sleep 1208 </dev/null >/dev/null 2>&1 & echo $PPID")
    daemon = until(lambda: sleeps(1208))
    os.kill(tracer, signal.SIGQUIT)
    gone = until(lambda: not alive(tracer) and not sleeps(1208))
    kill(sleeps(1208) + [tracer])
    return daemon and gone, "daemon=%r" % daemon


@test("after vpid 1's reap, TERM and HUP to the caller's group leave the tracer and a daemon")
def _():
    # The daemon ignores both signals and stays in the caller's group.
    proc = start([P, "sh", "-c", "trap '' TERM HUP; sleep 1209 </dev/null >/dev/null 2>&1 & echo $PPID"],
                 start_new_session=True)
    tracer = int(proc.stdout.readline())
    proc.wait(5)
    daemon = until(lambda: sleeps(1209))
    os.killpg(proc.pid, signal.SIGTERM)
    os.killpg(proc.pid, signal.SIGHUP)
    time.sleep(0.5)
    ok = daemon and alive(tracer) and sleeps(1209)
    kill(sleeps(1209) + [tracer])
    return ok, "daemon=%r tracer alive=%r" % (daemon, alive(tracer))


# --- dispositions ---

@test("a program under nohup ignores SIGHUP")
def _():
    r = subprocess.run(["nohup", P, CATCH, "disp"], capture_output=True, timeout=10)
    return b"HUP=ign" in r.stdout, repr(r.stdout)


@test("a program under trap '' INT ignores SIGINT")
def _():
    r = subprocess.run(["sh", "-c", "trap '' INT; exec \"$0\" \"$1\" disp", P, CATCH],
                       capture_output=True, timeout=10)
    return b"INT=ign" in r.stdout, repr(r.stdout)


# --- before vpid 1 exists ---

@test("SIGTERM to the waiter before vpid 1 exists ends the tracer")
def _():
    # strace holds each clone(2) for 2 s, the tracer's fork of vpid 1 included.
    proc = start(["strace", "-f", "-qq", "-o", "/dev/null", "-e", "trace=clone,clone3",
                  "-e", "inject=clone:delay_enter=2000000", P, CATCH, "0"])
    waiter = child_of(proc.pid)
    tracer = until(lambda: children(waiter) if waiter else None, 8)
    if waiter:
        os.kill(waiter, signal.SIGTERM)
    out, _ = proc.communicate(timeout=20)
    return waiter and tracer and out == b"" and not alive(tracer[0]), \
        "waiter=%r tracer=%r out=%r" % (waiter, tracer, out)


# --- --sysvipc ---

@test("--sysvipc through the dynamic linker, its helper's pid, and $(proot --sysvipc ... 2>&1)")
def _():
    tmp = os.path.join(TMP, "shm")
    os.makedirs(tmp, exist_ok=True)
    env = dict(os.environ, PROOT_TMP_DIR=tmp)
    proc = subprocess.Popen([INTERP, P, "--sysvipc", CATCH, "shm"], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
    got = proc.stdout.readline()
    names = [n for n in os.listdir(tmp) if n.startswith("prootshm-")]
    helper = [n for n in names if b"--shm-helper" in cmdline(int(n.split("-")[1]))]
    proc.stdin.close()
    try:
        proc.stdout.read()
        proc.wait(10)
        returned = True
    except subprocess.TimeoutExpired:
        returned = False
        proc.kill()
    return got == b"shm ok\n" and helper and returned, \
        "got=%r names=%r returned=%r" % (got, names, returned)


def expire(signum, frame):
    raise TimeoutError("no result in 30 s")


signal.signal(signal.SIGALRM, expire)
failed = 0
for name, function in TESTS:
    signal.alarm(30)
    try:
        ok, why = function()
    except Exception as e:
        ok, why = False, "%s: %s" % (type(e).__name__, e)
    signal.alarm(0)
    if ok:
        print("ok   " + name)
    else:
        failed += 1
        print("FAIL %s: %s" % (name, why))
    sys.stdout.flush()
sys.exit(1 if failed else 0)
