#!/usr/bin/env python3
"""The system calls Android's app policy traps, for each ABI.

For API levels 30 to 36, bionic-seccomp/<level>/ holds bionic's libc/SYSCALLS.TXT,
its SECCOMP_*_COMMON.TXT and SECCOMP_*_APP.TXT lists, and the kernel headers
libc/seccomp/gen_syscall_nrs*.cpp include, from the release tag of that level:

    30 android-11.0.0_r1    33 android-13.0.0_r1    36 android-16.0.0_r1
    31 android-12.0.0_r1    34 android-14.0.0_r1
    32 android-12.1.0_r1    35 android-15.0.0_r1

The policy genseccomp.py builds from them allows (SYSCALLS.TXT - blocklist) |
allowlist and traps every other call in the ABI's table. A call is listed when
some level traps it. Blocked names are the calls a blocklist names at some level.

Usage: trapped-calls.py DIR           print "ABI NAME" for each trapped call
       trapped-calls.py DIR --check FILE [SECCOMP_C]
                                       fail when FILE does not mark a trapped
                                       call, marks a call no level traps, marks
                                       a blocked name other than EPERM or a
                                       translation, or, given SECCOMP_C, marks a
                                       call translated, upstream or EPERM that
                                       has no case there
       trapped-calls.py DIR --header FILE
                                       print trapped-calls.h: TRAPPED(NUMBER)
                                       for each call FILE lists, by ABI

DIR/fetch.sh fetches DIR from bionic, and DIR/NOTICE gives its licenses.
"""
import os
import re
import subprocess
import sys

LEVELS = range(30, 37)
ABIS = {
    # ABI: (bionic's architecture name, include directory, header)
    "arm64-v8a": ("arm64", "asm-arm64", "asm/unistd.h"),
    "x86_64": ("x86_64", "asm-x86", "asm/unistd_64.h"),
}
MARKS = ("translated", "upstream", "ENOSYS", "EPERM")


def names(path, arch):
    """The syscall names a SYSCALLS.TXT-format file gives for @arch."""
    result = set()
    if not os.path.exists(path):
        return result
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        # [return type] func[|aliases][:syscall[:socketcall id]](...) archs
        func = line[:line.index("(")].split()[-1]
        archs = line[line.rindex(")") + 1:].strip().split(",")
        if not ("all" in archs or arch in archs
                or ("lp64" in archs and "64" in arch)):
            continue
        name = func.split(":")[1] if ":" in func else func
        result.add(name.split("|")[0].strip())
    return result


def table(level_dir, include, header):
    """The __NR_ names the kernel headers define."""
    uapi = os.path.join(level_dir, "kernel", "uapi")
    out = subprocess.run(
        ["cpp", "-dM", "-nostdinc", "-I", os.path.join(uapi, include),
         "-I", uapi, "-"],
        input="#include <%s>\n" % header, capture_output=True, text=True,
        check=True).stdout
    return set(re.findall(r"^#define __NR_(\w+) ", out, re.M)) - {"syscalls"}


def numbers(level_dir, include, header, wanted):
    """{name: number} for each of @wanted the kernel headers define."""
    uapi = os.path.join(level_dir, "kernel", "uapi")
    source = "#include <%s>\n" % header + "".join(
        "@%s __NR_%s\n" % (n, n) for n in sorted(wanted))
    out = subprocess.run(
        ["cpp", "-P", "-nostdinc", "-I", os.path.join(uapi, include), "-I", uapi, "-"],
        input=source, capture_output=True, text=True, check=True).stdout
    result = {}
    for name, value in re.findall(r"^@(\w+) (.+)$", out, re.M):
        if not value.startswith("__NR_"):
            result[name] = eval(value)
    return result


def header(root, marks_path):
    """trapped-calls.h, with the numbers of the calls @marks_path lists."""
    listed = {}
    for line in open(marks_path):
        line = line.split("#")[0].split()
        if line:
            listed.setdefault(line[0], set()).add(line[1])
    out = ["/* The system calls Android's app policy traps at some API level from",
           " * 30 to 36, by number: trapped-calls.txt as tests/trapped-calls.py",
           " * --header prints it.  */",
           ""]
    guards = {"arm64-v8a": "ARCH_ARM64", "x86_64": "ARCH_X86_64"}
    for i, (abi, (arch, include, hdr)) in enumerate(ABIS.items()):
        found = {}
        for level in LEVELS:
            found.update(numbers(os.path.join(root, str(level)), include, hdr, listed[abi]))
        missing = listed[abi] - set(found)
        if missing:
            raise SystemExit("%s: no number for %s" % (abi, " ".join(sorted(missing))))
        out.append("#%s defined(%s)" % ("if" if i == 0 else "elif", guards[abi]))
        for name in sorted(found, key=lambda n: found[n]):
            out.append("TRAPPED(%d) /* %s */" % (found[name], name))
    out.append("#endif")
    return "\n".join(out) + "\n"


def list_file(level_dir, kind, which):
    # Android 11 names the lists WHITELIST and BLACKLIST.
    old = {"ALLOWLIST": "WHITELIST", "BLOCKLIST": "BLACKLIST"}[kind]
    for k in (kind, old):
        path = os.path.join(level_dir, "SECCOMP_%s_%s.TXT" % (k, which))
        if os.path.exists(path):
            return path
    raise SystemExit("%s: no SECCOMP_%s_%s.TXT" % (level_dir, kind, which))


def trapped(root):
    """{ABI: (trapped names, blocked names)} over every level."""
    result = {}
    for abi, (arch, include, header) in ABIS.items():
        traps, blocked = set(), set()
        for level in LEVELS:
            d = os.path.join(root, str(level))
            block = set()
            for which in ("COMMON", "APP"):
                block |= names(list_file(d, "BLOCKLIST", which), arch)
            allow = set()
            for which in ("COMMON", "APP"):
                allow |= names(list_file(d, "ALLOWLIST", which), arch)
            allowed = (names(os.path.join(d, "SYSCALLS.TXT"), arch) - block) | allow
            traps |= table(d, include, header) - allowed
            blocked |= block
        result[abi] = (traps, blocked)
    return result


def check(root, marks_path, seccomp_c):
    result = trapped(root)
    marked = {}
    for n, line in enumerate(open(marks_path), 1):
        line = line.split("#")[0].split()
        if not line:
            continue
        if len(line) != 3 or line[0] not in ABIS or line[2] not in MARKS:
            raise SystemExit("%s:%d: expected 'ABI NAME MARK'" % (marks_path, n))
        marked[(line[0], line[1])] = line[2]
    cases = set(re.findall(r"case PR_(\w+):", open(seccomp_c).read())) if seccomp_c else None
    errors = []
    for abi, (traps, blocked) in result.items():
        for name in sorted(traps):
            mark = marked.get((abi, name))
            if mark is None:
                errors.append("%s %s: trapped and not marked" % (abi, name))
            elif name in blocked and mark not in ("EPERM", "translated", "upstream"):
                errors.append("%s %s: blocked, marked %s" % (abi, name, mark))
            elif name not in blocked and mark == "EPERM":
                errors.append("%s %s: marked EPERM and no blocklist names it" % (abi, name))
            elif cases is not None and mark != "ENOSYS" and name not in cases:
                errors.append("%s %s: marked %s and seccomp.c has no case" % (abi, name, mark))
    for (abi, name) in sorted(marked):
        if name not in result[abi][0]:
            errors.append("%s %s: marked and no level traps it" % (abi, name))
    for e in errors:
        print(e)
    return 1 if errors else 0


def main(argv):
    if len(argv) == 4 and argv[2] == "--header":
        sys.stdout.write(header(argv[1], argv[3]))
        return 0
    if len(argv) >= 4 and argv[2] == "--check":
        return check(argv[1], argv[3], argv[4] if len(argv) > 4 else None)
    if len(argv) != 2:
        raise SystemExit(__doc__)
    for abi, (traps, _) in trapped(argv[1]).items():
        for name in sorted(traps):
            print(abi, name)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
