/*
 * Exec routing. Derived from termux-exec (termux-play-store/termux-exec, 47633d5,
 * Apache-2.0); see NOTICE.
 *
 * An app targeting API 29 or later may map a file in its data directory
 * executable but may not execve it. Every exec is decided here, first matching
 * rule wins:
 *
 *   0. /proc/self/exe of a process started through a loader is the program it
 *      was started as.
 *   1. /bin/x and /usr/bin/x are $HARNESS_PREFIX/bin/x when that file exists.
 *   2. A shebang's interpreter is decided in the script's place, to a depth of
 *      four, with its argument and the script in front of the arguments.
 *   3. The target is classified by its realpath:
 *
 *      row  target                           runs as
 *      1    linker64                         as asked
 *      2    the loader                       as asked, shim prepended
 *      3    the shim                         as asked
 *      4    native dynamic ELF under system  as asked
 *      5    anything else under system       as asked
 *      6    PT_INTERP /system/bin/linker64   /system/bin/linker64 P ...
 *      7    PT_INTERP ld-musl-*              [shim] loader --argv0 argv0 P ...
 *      8    anything else                    as asked, environment unchanged
 *
 * Everything here is safe in a vfork child: raw system calls, no allocator, no
 * stdio, no writes to environ. The new argument and environment arrays live in
 * one anonymous mapping sized from the caller's.
 */
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "harness-exec.h"

#ifdef HX_MUSL
#include "syscall.h"
#define hx_sys(...) __syscall(__VA_ARGS__)
#else
static long hx_ret(long r) { return r == -1 ? -errno : r; }
#define hx_sys(...) hx_ret(syscall(__VA_ARGS__))
#endif

#if defined(__aarch64__)
#define HX_MACHINE EM_AARCH64
#define HX_USE_SHIM 0
#elif defined(__x86_64__)
#define HX_MACHINE EM_X86_64
/* Android's seccomp allowlist omits the legacy x86_64 system calls musl makes,
 * so a musl program there runs under the tracer that rewrites them. */
#define HX_USE_SHIM 1
#else
#error "unsupported architecture"
#endif

#define HX_DEPTH 4
/* The kernel reads at most this much of a shebang line. */
#define HX_HEADER 256
#define HX_PSE "TERMUX_EXEC__PROC_SELF_EXE"

#define HX_LOADER "libmuslloader.so"
#define HX_SHIM "libsyscallshim.so"
#define HX_PRELOAD "libtermuxexec.so"

static const char *hx_linker64 = "/system/bin/linker64";

static const char *const hx_linkers[] = {
    "/system/bin/linker64",
    "/apex/com.android.runtime/bin/linker64",
    "/system/bin/bootstrap/linker64",
};

static const char *const hx_system_dirs[] = {
    "/system", "/system_ext", "/apex", "/vendor", "/product", "/odm", "/data/app",
};

/* Taken once from where the router is loaded, never from a child's
 * environment. Every path is a realpath. */
static struct {
	char libdir[PATH_MAX];
	char loader[PATH_MAX];
	char shim[PATH_MAX];
	char preload[PATH_MAX];
	char linker[sizeof hx_linkers / sizeof *hx_linkers][PATH_MAX];
	char prefix[PATH_MAX];
	char etc[PATH_MAX];
	/* TERMUX_EXEC__PROC_SELF_EXE as the process was started. */
	char self[PATH_MAX];
	int use_shim;
} hx_cfg;

/* The working state of one decision, at the head of the mapping. */
struct hx_scratch {
	char path[HX_DEPTH + 1][PATH_MAX];
	char header[HX_DEPTH + 1][HX_HEADER];
	char interp[HX_HEADER];
	char norm[PATH_MAX];
	char canon[PATH_MAX];
	char pse[PATH_MAX];
	char tmp[PATH_MAX];
	const char *head[2 * HX_DEPTH + 2];
};

enum { HX_KEEP, HX_OURS, HX_DROP };

struct hx_env {
	int preload;
	int libpath;
	int shim;
	const char *pse;
};

static void hx_proc_path(char *out, const char *a, unsigned long n, const char *b) {
	char digits[24], *d = digits + sizeof digits;
	size_t la = strlen(a), lb = strlen(b), ld;
	*--d = 0;
	do
		*--d = (char)('0' + n % 10);
	while (n /= 10);
	ld = strlen(d);
	memcpy(out, a, la);
	memcpy(out + la, d, ld);
	memcpy(out + la + ld, b, lb + 1);
}

static int hx_copy(char *dst, const char *src) {
	size_t n = strlen(src);
	if (n >= PATH_MAX) return ENAMETOOLONG;
	memmove(dst, src, n + 1);
	return 0;
}

/* dst = a "/" b. dst may be a; b may not overlap dst. */
static int hx_join(char *dst, const char *a, const char *b) {
	size_t la = strlen(a), lb = strlen(b);
	if (la + 1 + lb >= PATH_MAX) return ENAMETOOLONG;
	if (dst != a) memcpy(dst, a, la);
	dst[la] = '/';
	memcpy(dst + la + 1, b, lb + 1);
	return 0;
}

static int hx_is(const char *entry, const char *key) {
	size_t n = strlen(key);
	return !strncmp(entry, key, n) && entry[n] == '=';
}

static const char *hx_env_get(char *const envp[], const char *key) {
	for (; *envp; envp++)
		if (hx_is(*envp, key)) return *envp + strlen(key) + 1;
	return 0;
}

/* The target of a symbolic link, NUL-terminated. */
static int hx_link_sys(const char *path, char *out) {
	long n = hx_sys(SYS_readlinkat, AT_FDCWD, path, out, PATH_MAX - 1);
	if (n < 0) return (int)-n;
	out[n] = 0;
	return 0;
}

/* realpath by way of the kernel. out may be path. */
static int hx_canon_sys(const char *path, char *out) {
	char proc[64];
	long fd = hx_sys(SYS_openat, AT_FDCWD, path, O_PATH | O_CLOEXEC);
	int e;
	if (fd < 0) return (int)-fd;
	hx_proc_path(proc, "/proc/self/fd/", (unsigned long)fd, "");
	e = hx_link_sys(proc, out);
	hx_sys(SYS_close, fd);
	return e;
}

/* The executable of this process's tracer, or empty. */
static void hx_tracer_sys(char *out) {
	char status[512], proc[64], *t;
	unsigned long pid = 0;
	long fd, n;
	out[0] = 0;
	fd = hx_sys(SYS_openat, AT_FDCWD, "/proc/self/status", O_RDONLY | O_CLOEXEC);
	if (fd < 0) return;
	n = hx_sys(SYS_read, fd, status, sizeof status - 1);
	hx_sys(SYS_close, fd);
	if (n <= 0) return;
	status[n] = 0;
	t = strstr(status, "\nTracerPid:");
	if (!t) return;
	for (t += 11; *t == ' ' || *t == '\t'; t++)
		;
	for (; *t >= '0' && *t <= '9'; t++)
		pid = pid * 10 + (unsigned long)(*t - '0');
	if (!pid) return;
	hx_proc_path(proc, "/proc/", pid, "/exe");
	if (hx_link_sys(proc, out)) out[0] = 0;
}

#ifdef UNIT_TEST
static int (*hx_link)(const char *, char *) = hx_link_sys;
static int (*hx_canon)(const char *, char *) = hx_canon_sys;
static void (*hx_tracer)(char *) = hx_tracer_sys;
#else
#define hx_link hx_link_sys
#define hx_canon hx_canon_sys
#define hx_tracer hx_tracer_sys
#endif

static int hx_is_linker(const char *real) {
	for (size_t i = 0; i < sizeof hx_cfg.linker / sizeof *hx_cfg.linker; i++)
		if (hx_cfg.linker[i][0] && !strcmp(real, hx_cfg.linker[i])) return 1;
	return 0;
}

/* Whether real, the realpath of a process's executable, is a loader. */
static int hx_loaded(const char *real) {
	return hx_is_linker(real) || (hx_cfg.loader[0] && !strcmp(real, hx_cfg.loader));
}

static int hx_under_system(const char *real) {
	for (size_t i = 0; i < sizeof hx_system_dirs / sizeof *hx_system_dirs; i++) {
		size_t n = strlen(hx_system_dirs[i]);
		if (!strncmp(real, hx_system_dirs[i], n) && (real[n] == '/' || !real[n])) return 1;
	}
	return 0;
}

static int hx_is_musl(const char *interp) {
	const char *base = strrchr(interp, '/');
	return !strncmp(base ? base + 1 : interp, "ld-musl-", 8);
}

/* Whether this process is the shim or is traced by it. */
static int hx_shimmed(char *tmp) {
	if (!hx_link("/proc/self/exe", tmp) && !strcmp(tmp, hx_cfg.shim)) return 1;
	hx_tracer(tmp);
	return tmp[0] && !strcmp(tmp, hx_cfg.shim);
}

/* Whether path is /proc/self/exe or /proc/<pid>/exe; pid is 0 for self. */
static int hx_proc_pid(const char *path, unsigned long *pid) {
	const char *p = path + 6;
	*pid = 0;
	if (strncmp(path, "/proc/", 6)) return 0;
	if (!strcmp(p, "self/exe")) return 1;
	if (*p < '0' || *p > '9') return 0;
	for (; *p >= '0' && *p <= '9'; p++)
		*pid = *pid * 10 + (unsigned long)(*p - '0');
	return !strcmp(p, "/exe");
}

static int hx_own(unsigned long pid) { return !pid || pid == (unsigned long)hx_sys(SYS_getpid); }

/* Absolute, without . and .. components, symbolic links left alone. */
static int hx_normalize(const char *p, char *out) {
	size_t n = 0;
	if (p[0] != '/') {
		long r = hx_sys(SYS_getcwd, out, PATH_MAX);
		if (r < 0) return (int)-r;
		n = strlen(out);
		if (n == 1) n = 0;
	}
	while (*p) {
		const char *e;
		size_t l;
		while (*p == '/')
			p++;
		for (e = p; *e && *e != '/'; e++)
			;
		l = (size_t)(e - p);
		if (!l) break;
		if (l == 2 && p[0] == '.' && p[1] == '.') {
			while (n && out[n - 1] != '/')
				n--;
			if (n) n--;
		} else if (l != 1 || p[0] != '.') {
			if (n + 1 + l >= PATH_MAX) return ENAMETOOLONG;
			out[n++] = '/';
			memcpy(out + n, p, l);
			n += l;
		}
		p = e;
	}
	if (!n) out[n++] = '/';
	out[n] = 0;
	return 0;
}

/* Step 1, in place. */
static void hx_rewrite(char *p, char *tmp) {
	const char *rest;
	if (!hx_cfg.prefix[0]) return;
	if (!strncmp(p, "/bin/", 5))
		rest = p + 5;
	else if (!strncmp(p, "/usr/bin/", 9))
		rest = p + 9;
	else
		return;
	if (hx_join(tmp, hx_cfg.prefix, "bin") || hx_join(tmp, tmp, rest)) return;
	if (hx_sys(SYS_faccessat, AT_FDCWD, tmp, F_OK, 0) == 0) memcpy(p, tmp, strlen(tmp) + 1);
}

/* The interpreter and its one optional argument, from a NUL-terminated header
 * of n bytes. The argument is the rest of the line without surrounding blanks. */
static int hx_shebang(char *h, size_t n, char **interp, char **arg) {
	char *end = memchr(h, '\n', n), *p, *q, *t;
	if (!end) end = h + n;
	*end = 0;
	for (p = h + 2; *p == ' ' || *p == '\t'; p++)
		;
	if (!*p) return ENOEXEC;
	for (q = p; *q && *q != ' ' && *q != '\t'; q++)
		;
	*interp = p;
	*arg = 0;
	if (!*q) return 0;
	*q++ = 0;
	while (*q == ' ' || *q == '\t')
		q++;
	for (t = q + strlen(q); t > q && (t[-1] == ' ' || t[-1] == '\t');)
		*--t = 0;
	if (*q) *arg = q;
	return 0;
}

/* Whether an ELF of n header bytes open as fd is native, and its PT_INTERP. */
static void hx_elf(long fd, const char *h, size_t n, int *native, char *interp) {
	Elf64_Ehdr eh;
	interp[0] = 0;
	*native = 0;
	if (n < sizeof eh || h[EI_CLASS] != ELFCLASS64) return;
	memcpy(&eh, h, sizeof eh);
	*native = eh.e_machine == HX_MACHINE;
	for (unsigned i = 0; i < eh.e_phnum && i < 64; i++) {
		Elf64_Phdr ph;
		long r = hx_sys(SYS_pread64, fd, &ph, sizeof ph, (long)(eh.e_phoff + (uint64_t)i * eh.e_phentsize));
		if (r != (long)sizeof ph) return;
		if (ph.p_type != PT_INTERP) continue;
		r = hx_sys(SYS_pread64, fd, interp, ph.p_filesz < HX_HEADER ? ph.p_filesz : HX_HEADER - 1,
		           (long)ph.p_offset);
		interp[r > 0 ? r : 0] = 0;
		return;
	}
}

/* The program a loader's arguments name, after its options. */
static const char *hx_loader_program(char *const *av) {
	for (; *av; av++) {
		const char *o = *av;
		if (o[0] != '-' || o[1] != '-') return o;
		o += 2;
		if (!*o) return av[1];
		if (!strcmp(o, "list") || !strncmp(o, "library-path=", 13) || !strncmp(o, "preload=", 8) ||
		    !strncmp(o, "argv0=", 6))
			continue;
		if (strcmp(o, "library-path") && strcmp(o, "preload") && strcmp(o, "argv0")) return 0;
		if (!*++av) return 0;
	}
	return 0;
}

static char *hx_put(char **area, const char *a, const char *b) {
	char *s = *area;
	size_t la = strlen(a), lb = strlen(b);
	memcpy(s, a, la);
	memcpy(s + la, b, lb + 1);
	*area = s + la + lb + 1;
	return s;
}

/* An LD_PRELOAD entry naming a termux-exec preload. */
static int hx_termux_exec(const char *e, size_t n) {
	const char *b = e + n;
	size_t l;
	while (b > e && b[-1] != '/')
		b--;
	l = (size_t)(e + n - b);
	return (l >= 14 && !memcmp(b, "libtermux-exec", 14)) || (l == 16 && !memcmp(b, HX_PRELOAD, 16));
}

/* LD_PRELOAD with ours first and no other termux-exec. */
static char *hx_preload(char **area, const char *old) {
	char *s = hx_put(area, "LD_PRELOAD=", hx_cfg.preload), *w = *area - 1;
	while (old && *old) {
		size_t n = strcspn(old, ": ");
		if (n && !hx_termux_exec(old, n)) {
			*w++ = ':';
			memcpy(w, old, n);
			w += n;
		}
		old += n;
		if (*old) old++;
	}
	*w++ = 0;
	*area = w;
	return s;
}

static char **hx_environ(char **out, char *area, char *const envp[], const struct hx_env *e, const char *old) {
	size_t k = 0;
	for (; *envp; envp++) {
		const char *v = *envp;
		if ((e->preload != HX_KEEP && hx_is(v, "LD_PRELOAD")) || (e->libpath == HX_DROP && hx_is(v, "LD_LIBRARY_PATH")) ||
		    hx_is(v, HX_PSE) || (hx_cfg.prefix[0] && hx_is(v, "HARNESS_PREFIX")) ||
		    (hx_cfg.etc[0] && hx_is(v, "HARNESS_ETC")) || (e->shim && hx_cfg.etc[0] && hx_is(v, "SYSCALL_SHIM_ETC")))
			continue;
		out[k++] = (char *)v;
	}
	if (e->preload == HX_OURS) out[k++] = hx_preload(&area, old);
	if (e->pse) out[k++] = hx_put(&area, HX_PSE "=", e->pse);
	if (hx_cfg.prefix[0]) out[k++] = hx_put(&area, "HARNESS_PREFIX=", hx_cfg.prefix);
	if (hx_cfg.etc[0]) out[k++] = hx_put(&area, "HARNESS_ETC=", hx_cfg.etc);
	if (e->shim && hx_cfg.etc[0]) out[k++] = hx_put(&area, "SYSCALL_SHIM_ETC=", hx_cfg.etc);
	out[k] = 0;
	return out;
}

static int hx_row(const char *real, int native, const char *interp) {
	if (hx_is_linker(real)) return 1;
	if (hx_cfg.loader[0] && !strcmp(real, hx_cfg.loader)) return 2;
	if (hx_cfg.shim[0] && !strcmp(real, hx_cfg.shim)) return 3;
	if (hx_under_system(real)) return native && interp[0] ? 4 : 5;
	if (!strcmp(interp, "/system/bin/linker64")) return 6;
	if (interp[0] && hx_is_musl(interp)) return 7;
	return 8;
}

static int hx_decide(const char *path, char *const argv[], char *const envp[], const char *base, size_t argc,
                     size_t envc, const char *old, struct hx_exec *x) {
	struct hx_scratch *s = x->map;
	char **av = (char **)(s + 1) + 5;
	char **ev = av + argc + 2 * HX_DEPTH + 4;
	char *area = (char *)(ev + envc + 8);
	const char **head = s->head, *head0, *prog;
	char *const *tail = argv[0] ? argv + 1 : argv;
	char *p = s->path[0];
	size_t hn = 0, depth, i, k = 0;
	unsigned long pid;
	int native = 0, row, shim = 0, e;
	struct hx_env env = {HX_KEEP, HX_KEEP, 0, 0};

	if (argv[0]) head[hn++] = argv[0];
	e = base && path[0] != '/' ? hx_join(p, base, path) : hx_copy(p, path);
	if (e) return e;

	/* Step 0. */
	if (hx_cfg.self[0] && hx_proc_pid(p, &pid) && hx_own(pid) && !hx_link("/proc/self/exe", s->tmp) &&
	    hx_loaded(s->tmp))
		memcpy(p, hx_cfg.self, strlen(hx_cfg.self) + 1);

	for (depth = 0;; depth++) {
		char *h = s->header[depth], *interp, *arg;
		long fd, n, r;
		size_t add, keep;
		p = s->path[depth];

		/* Step 1. */
		hx_rewrite(p, s->tmp);
		r = hx_sys(SYS_faccessat, AT_FDCWD, p, X_OK, 0);
		if (r < 0) return (int)-r;

		/* A file that cannot be read is classified by its path alone. */
		s->interp[0] = 0;
		fd = hx_sys(SYS_openat, AT_FDCWD, p, O_RDONLY | O_CLOEXEC);
		if (fd < 0) break;
		n = hx_sys(SYS_pread64, fd, h, HX_HEADER - 1, 0);
		if (n < 0) {
			hx_sys(SYS_close, fd);
			return n == -EISDIR ? EACCES : (int)-n;
		}
		h[n] = 0;
		if (n >= SELFMAG && !memcmp(h, ELFMAG, SELFMAG)) {
			hx_elf(fd, h, (size_t)n, &native, s->interp);
			hx_sys(SYS_close, fd);
			break;
		}
		hx_sys(SYS_close, fd);

		/* Step 2. */
		if (n < 2 || h[0] != '#' || h[1] != '!' || hx_shebang(h, (size_t)n, &interp, &arg)) return ENOEXEC;
		if (depth == HX_DEPTH) return ELOOP;
		if ((e = hx_copy(s->path[depth + 1], interp))) return e;
		add = arg ? 3 : 2;
		keep = hn ? hn - 1 : 0;
		memmove(head + add, head + 1, keep * sizeof *head);
		head[0] = interp;
		if (arg) head[1] = arg;
		head[add - 1] = p;
		hn = add + keep;
	}

	/* Step 3. */
	if (hx_normalize(p, s->norm)) hx_copy(s->norm, p);
	if (hx_canon(p, s->canon)) memcpy(s->canon, s->norm, strlen(s->norm) + 1);
	row = hx_row(s->canon, native, s->interp);
	if ((row == 2 || row == 7) && hx_cfg.use_shim && hx_cfg.shim[0] && !hx_shimmed(s->tmp)) shim = 1;

	for (i = 0; i < hn; i++)
		av[k++] = (char *)head[i];
	for (i = 0; tail[i]; i++)
		av[k++] = tail[i];
	av[k] = av[k + 1] = 0;
	head0 = hn ? head[0] : s->norm;
	x->path = p;

	switch (row) {
	case 1:
		env = (struct hx_env){HX_OURS, HX_KEEP, 0, 0};
		if (av[0] && av[1] && !hx_normalize(av[1], s->pse)) env.pse = s->pse;
		break;
	case 2:
		if (shim) {
			/* The shim runs the loader, which this router in the shim then
			 * decides again, without the shim. */
			env = (struct hx_env){HX_OURS, HX_KEEP, 1, 0};
			av[0] = s->norm;
			*--av = hx_cfg.shim;
			x->path = hx_cfg.shim;
		} else {
			env = (struct hx_env){HX_DROP, HX_DROP, 0, 0};
			prog = av[0] ? hx_loader_program(av + 1) : 0;
			if (prog && !hx_normalize(prog, s->pse)) env.pse = s->pse;
		}
		break;
	case 3:
		env = (struct hx_env){HX_OURS, HX_KEEP, 1, 0};
		break;
	case 4:
		env = (struct hx_env){HX_OURS, HX_DROP, 0, 0};
		break;
	case 5:
		env = (struct hx_env){HX_DROP, HX_DROP, 0, 0};
		break;
	case 6:
		env = (struct hx_env){HX_OURS, HX_KEEP, 0, s->norm};
		av[0] = s->norm;
		*--av = (char *)head0;
		x->path = hx_linker64;
		break;
	case 7:
		av[0] = s->norm;
		*--av = (char *)head0;
		*--av = (char *)"--argv0";
		*--av = hx_cfg.loader;
		x->path = hx_cfg.loader;
		if (shim) {
			env = (struct hx_env){HX_OURS, HX_KEEP, 1, 0};
			*--av = hx_cfg.shim;
			x->path = hx_cfg.shim;
		} else {
			env = (struct hx_env){HX_DROP, HX_DROP, 0, s->norm};
		}
		break;
	default:
		x->argv = av;
		x->envp = (char **)envp;
		return 0;
	}
	x->argv = av;
	x->envp = hx_environ(ev, area, envp, &env, old);
	return 0;
}

int hx_route(const char *path, char *const argv[], char *const envp[], const char *base, struct hx_exec *x) {
	static char *const none[] = {0};
	const char *old;
	size_t argc = 0, envc = 0;
	long m;
	int e;

	x->map = 0;
	if (!path) return EFAULT;
	if (!argv) argv = none;
	if (!envp) envp = none;
	while (argv[argc])
		argc++;
	while (envp[envc])
		envc++;
	old = hx_env_get(envp, "LD_PRELOAD");
	x->len = sizeof(struct hx_scratch) + (argc + envc + 2 * HX_DEPTH + 32) * sizeof(char *) + 6 * PATH_MAX + 128 +
	         (old ? strlen(old) : 0);
	m = hx_sys(SYS_mmap, 0, x->len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	if (m < 0 && m > -4096) return E2BIG;
	x->map = (void *)m;
	e = hx_decide(path, argv, envp, base, argc, envc, old, x);
	if (e) hx_release(x);
	return e;
}

int hx_route_search(const char *file, const char *search, char *const argv[], char *const envp[], const char *base,
                    struct hx_exec *x) {
	char b[PATH_MAX];
	const char *p, *z;
	size_t k;
	int seen = 0;

	x->map = 0;
	if (!file) return EFAULT;
	if (!*file) return ENOENT;
	if (strchr(file, '/')) return hx_route(file, argv, envp, base, x);
	k = strlen(file);
	if (k > NAME_MAX) return ENAMETOOLONG;
	for (p = search;; p = z + 1) {
		size_t l;
		z = strchrnul(p, ':');
		l = (size_t)(z - p);
		if (l + 1 + k < PATH_MAX) {
			int e;
			/* An empty entry is the working directory. */
			memcpy(b, p, l);
			b[l] = '/';
			memcpy(b + l + (l > 0), file, k + 1);
			e = hx_route(b, argv, envp, base, x);
			if (!e) return 0;
			if (e == EACCES)
				seen = 1;
			else if (e != ENOENT && e != ENOTDIR)
				return e;
		}
		if (!*z) break;
	}
	return seen ? EACCES : ENOENT;
}

void hx_release(struct hx_exec *x) {
	if (x->map) hx_sys(SYS_munmap, x->map, x->len);
	x->map = 0;
}

int hx_execve(const char *path, char *const argv[], char *const envp[]) {
	struct hx_exec x;
	int e = hx_route(path, argv, envp, 0, &x);
	long r;
	if (e) return -e;
	r = hx_sys(SYS_execve, x.path, x.argv, x.envp);
	hx_release(&x);
	return (int)r;
}

/* TERMUX_EXEC__PROC_SELF_EXE from /proc/<pid>/environ, read in pieces. */
static int hx_environ_of(unsigned long pid, char *out) {
	static const char key[] = HX_PSE "=";
	char proc[64], chunk[512];
	size_t pos = 0, vl = 0;
	int match = 1;
	long fd, n;
	hx_proc_path(proc, "/proc/", pid, "/environ");
	fd = hx_sys(SYS_openat, AT_FDCWD, proc, O_RDONLY | O_CLOEXEC);
	if (fd < 0) return (int)-fd;
	while ((n = hx_sys(SYS_read, fd, chunk, sizeof chunk)) > 0) {
		for (long i = 0; i < n; i++) {
			char c = chunk[i];
			if (!c) {
				if (match && pos >= sizeof key - 1) goto found;
				pos = vl = 0;
				match = 1;
				continue;
			}
			if (pos < sizeof key - 1)
				match &= c == key[pos];
			else if (match && vl < PATH_MAX - 1)
				out[vl++] = c;
			pos++;
		}
	}
	if (!(match && pos >= sizeof key - 1)) {
		hx_sys(SYS_close, fd);
		return ENOENT;
	}
found:
	hx_sys(SYS_close, fd);
	out[vl] = 0;
	return vl ? 0 : ENOENT;
}

const char *hx_proc_exe(const char *path, char *buf) {
	unsigned long pid;
	if (!hx_proc_pid(path, &pid) || hx_link(path, buf) || !hx_loaded(buf)) return 0;
	if (hx_own(pid)) {
		if (!hx_cfg.self[0]) return 0;
		memcpy(buf, hx_cfg.self, strlen(hx_cfg.self) + 1);
	} else if (hx_environ_of(pid, buf)) {
		return 0;
	}
	hx_canon(buf, buf);
	return buf;
}

const char *hx_etc_path(const char *name, char *buf) {
	const char *etc = hx_cfg.etc[0] ? hx_cfg.etc : getenv("HARNESS_ETC");
	if (!etc || !*etc || strncmp(name, "/etc/", 5) || hx_join(buf, etc, name + 5)) return name;
	return buf;
}

int hx_chdir(char *dir, const char *path, int fd) {
	char proc[64];
	if (!path) {
		hx_proc_path(proc, "/proc/self/fd/", (unsigned long)fd, "");
		return hx_link(proc, dir);
	}
	if (path[0] == '/') return hx_copy(dir, path);
	if (!dir[0]) {
		long r = hx_sys(SYS_getcwd, dir, PATH_MAX);
		if (r < 0) return (int)-r;
	}
	return hx_join(dir, dir, path);
}

static void hx_take(char *dst, char *const envp[], const char *key) {
	const char *v = envp ? hx_env_get(envp, key) : 0;
	if (!v || hx_copy(dst, v)) dst[0] = 0;
}

void hx_capture(char *const envp[], const char *libdir) {
	char dir[PATH_MAX];
	hx_take(hx_cfg.prefix, envp, "HARNESS_PREFIX");
	hx_take(hx_cfg.etc, envp, "HARNESS_ETC");
	hx_take(hx_cfg.self, envp, HX_PSE);
	if (!libdir) {
		char *slash;
		if (hx_link("/proc/self/exe", dir)) dir[0] = 0;
		slash = strrchr(dir, '/');
		if (slash) *slash = 0;
		libdir = dir;
	}
	if (!libdir[0] || (hx_canon(libdir, hx_cfg.libdir) && hx_copy(hx_cfg.libdir, libdir))) hx_cfg.libdir[0] = 0;
	if (!hx_cfg.libdir[0] || hx_join(hx_cfg.loader, hx_cfg.libdir, HX_LOADER) ||
	    hx_join(hx_cfg.shim, hx_cfg.libdir, HX_SHIM) || hx_join(hx_cfg.preload, hx_cfg.libdir, HX_PRELOAD))
		hx_cfg.loader[0] = hx_cfg.shim[0] = hx_cfg.preload[0] = 0;
	for (size_t i = 0; i < sizeof hx_linkers / sizeof *hx_linkers; i++)
		if (hx_canon(hx_linkers[i], hx_cfg.linker[i])) hx_cfg.linker[i][0] = 0;
	hx_cfg.use_shim = HX_USE_SHIM;
}
