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
 *      row  target                             runs as
 *      1    linker64 with a proot as argv[1]   as row 5, with argv[1] as P
 *      2    linker64                           as asked
 *      3    the loader                         as asked, shim prepended
 *      4    the shim                           as asked
 *      5    a proot                            libproot.so, arguments as asked
 *      6    native dynamic ELF under system    as asked
 *      7    anything else under system         as asked
 *      8    PT_INTERP linker64                 /system/bin/linker64 P ...
 *      9    PT_INTERP ld-musl-*                [shim] loader --argv0 argv0 P ...
 *      10   any other native ELF program       libproot.so --exec-bin DIR BINDS P ...
 *      11   anything else                      as asked, environment unchanged
 *
 *      A proot is libproot.so or the realpath of $HARNESS_PREFIX/bin/proot.
 *      Row 10's BINDS put $HARNESS_ETC's files, the certificates, the musl
 *      loader and glibc's ld.so at the paths a Linux program looks for them.
 *
 *   A process traced by a proot, directly or through linker64, runs rows 1 and
 *   5 as above, 3 and 9 without the shim, and 4 as its argv[1...]. Row 9 runs
 *   as asked when /lib/ld-musl-*.so.1 exists, as it does inside a row-10 proot.
 *   Every other exec runs as asked, and LD_PRELOAD loses its termux-exec
 *   entries, or all of them for a target with neither interpreter.
 *
 * Everything here is safe in a vfork child: raw system calls, no allocator, no
 * stdio, no writes to environ. The working state and the new argument and
 * environment arrays live in the caller's struct hx_exec; arrays too large for
 * it take one anonymous mapping.
 */
#define _GNU_SOURCE
#include <dirent.h>
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
#define HX_MUSL_LIB "/lib/ld-musl-aarch64.so.1"
#define HX_GLIBC_NAME "ld-linux-aarch64.so.1"
#define HX_GLIBC_LIB "/lib/ld-linux-aarch64.so.1"
#elif defined(__x86_64__)
#define HX_MACHINE EM_X86_64
/* Android's seccomp allowlist omits the legacy x86_64 system calls musl makes,
 * so a musl program there runs under the tracer that rewrites them. */
#define HX_USE_SHIM 1
#define HX_MUSL_LIB "/lib/ld-musl-x86_64.so.1"
#define HX_GLIBC_NAME "ld-linux-x86-64.so.2"
#define HX_GLIBC_LIB "/lib64/ld-linux-x86-64.so.2"
#else
#error "unsupported architecture"
#endif

#define HX_PSE "TERMUX_EXEC__PROC_SELF_EXE"
#define HX_TMP "PROOT_TMP_DIR"

#define HX_LOADER "libmuslloader.so"
#define HX_SHIM "libsyscallshim.so"
#define HX_PRELOAD "libtermuxexec.so"
#define HX_PROOT "libproot.so"

/* Row 10's --bind arguments at most. */
#define HX_BINDS 16
/* The arguments a row puts in front of the caller's: row 10's. */
#define HX_PRE (3 + HX_BINDS)

static const char *hx_linker64 = "/system/bin/linker64";
/* Where a row-10 proot binds the musl loader. */
static const char *hx_musl_lib = HX_MUSL_LIB;

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
	/* libproot.so, when it exists. */
	char proot[PATH_MAX];
	/* $HARNESS_PREFIX/bin, $HARNESS_PREFIX/bin/proot, and the
	 * PROOT_TMP_DIR entry naming $HARNESS_PREFIX/tmp. */
	char bin[PATH_MAX];
	char bin_proot[PATH_MAX];
	char tmp[PATH_MAX];
	/* glibc's ld.so under the prefix, bound when it exists. */
	char glibc[PATH_MAX];
	/* Row 10's --bind arguments: bind[0..nbind), where the last is glibc's
	 * when glibc_bind is set. */
	char bind_text[HX_BINDS][PATH_MAX];
	const char *bind[HX_BINDS];
	size_t nbind;
	int glibc_bind;
	/* /proc/self/maps, read through this at capture. */
	char maps[4096];
} hx_cfg;

/* LD_PRELOAD as it is, with ours first, removed, or with its termux-exec
 * entries removed. */
enum { HX_KEEP, HX_OURS, HX_DROP, HX_STRIP };
/* PROOT_TMP_DIR as it is, added when absent, or replaced. */
enum { HX_TMP_KEEP, HX_TMP_ADD, HX_TMP_SET };

struct hx_env {
	int preload;
	int libpath;
	int shim;
	const char *pse;
	int tmp;
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

/* The pid of this process's tracer, or 0, and its executable in out, or
 * empty. */
static unsigned long hx_tracer_sys(struct hx_scratch *s, char *out) {
	char proc[64], *status = s->tracer_status, *t;
	unsigned long pid = 0;
	long fd, n;
	out[0] = 0;
	fd = hx_sys(SYS_openat, AT_FDCWD, "/proc/self/status", O_RDONLY | O_CLOEXEC);
	if (fd < 0) return 0;
	n = hx_sys(SYS_read, fd, status, sizeof s->tracer_status - 1);
	hx_sys(SYS_close, fd);
	if (n <= 0) return 0;
	status[n] = 0;
	t = strstr(status, "\nTracerPid:");
	if (!t) return 0;
	for (t += 11; *t == ' ' || *t == '\t'; t++)
		;
	for (; *t >= '0' && *t <= '9'; t++)
		pid = pid * 10 + (unsigned long)(*t - '0');
	if (!pid) return 0;
	hx_proc_path(proc, "/proc/", pid, "/exe");
	if (hx_link_sys(proc, out)) out[0] = 0;
	return pid;
}

#ifdef UNIT_TEST
static int (*hx_link)(const char *, char *) = hx_link_sys;
static int (*hx_canon)(const char *, char *) = hx_canon_sys;
static unsigned long (*hx_tracer)(struct hx_scratch *, char *) = hx_tracer_sys;
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

/* Whether a PT_INTERP names linker64 by one of its paths. */
static int hx_is_linker_interp(const char *interp) {
	for (size_t i = 0; i < sizeof hx_linkers / sizeof *hx_linkers; i++)
		if (!strcmp(interp, hx_linkers[i])) return 1;
	return 0;
}

/* Whether real, a realpath, is a proot. s->proot holds the realpath of
 * $HARNESS_PREFIX/bin/proot. */
static int hx_is_proot(const struct hx_scratch *s, const char *real) {
	return hx_cfg.proot[0] && (!strcmp(real, hx_cfg.proot) || (s->proot[0] && !strcmp(real, s->proot)));
}

/* Whether the tracer pid, whose executable is exe, is a proot: itself, or
 * linker64 running one as its argv[1]. */
static int hx_proot_tracer(struct hx_scratch *s, unsigned long pid, const char *exe) {
	char proc[64], *c = s->tracer_cmdline, *arg1 = s->tracer_arg1;
	long fd, n;
	size_t l0;
	if (!pid || !exe[0]) return 0;
	if (hx_is_proot(s, exe)) return 1;
	if (!hx_is_linker(exe)) return 0;
	hx_proc_path(proc, "/proc/", pid, "/cmdline");
	fd = hx_sys(SYS_openat, AT_FDCWD, proc, O_RDONLY | O_CLOEXEC);
	if (fd < 0) return 0;
	n = hx_sys(SYS_read, fd, c, sizeof s->tracer_cmdline - 1);
	hx_sys(SYS_close, fd);
	if (n <= 0) return 0;
	c[n] = 0;
	l0 = strlen(c);
	if ((long)l0 + 1 >= n) return 0;
	c += l0 + 1;
	if (c[0] == '/') {
		if (hx_copy(arg1, c)) return 0;
	} else {
		hx_proc_path(arg1, "/proc/", pid, "/cwd");
		if (hx_join(arg1, arg1, c)) return 0;
	}
	return !hx_canon(arg1, arg1) && hx_is_proot(s, arg1);
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

/* Step 1, in place in p, which holds cap bytes. */
static void hx_rewrite(char *p, size_t cap, char *tmp) {
	const char *rest;
	if (!hx_cfg.prefix[0]) return;
	if (!strncmp(p, "/bin/", 5))
		rest = p + 5;
	else if (!strncmp(p, "/usr/bin/", 9))
		rest = p + 9;
	else
		return;
	if (hx_join(tmp, hx_cfg.prefix, "bin") || hx_join(tmp, tmp, rest) || strlen(tmp) >= cap) return;
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

/* Whether an ELF of n header bytes open as fd is native, and its PT_INTERP.
 * Returns its e_type, or 0 for an ELF that is not 64-bit. */
static int hx_elf(long fd, const char *h, size_t n, int *native, char *interp) {
	Elf64_Ehdr eh;
	interp[0] = 0;
	*native = 0;
	if (n < sizeof eh || h[EI_CLASS] != ELFCLASS64) return 0;
	memcpy(&eh, h, sizeof eh);
	*native = eh.e_machine == HX_MACHINE;
	for (unsigned i = 0; i < eh.e_phnum && i < 64; i++) {
		Elf64_Phdr ph;
		long r = hx_sys(SYS_pread64, fd, &ph, sizeof ph, (long)(eh.e_phoff + (uint64_t)i * eh.e_phentsize));
		if (r != (long)sizeof ph) break;
		if (ph.p_type != PT_INTERP) continue;
		r = hx_sys(SYS_pread64, fd, interp, ph.p_filesz < HX_HEADER ? ph.p_filesz : HX_HEADER - 1,
		           (long)ph.p_offset);
		interp[r > 0 ? r : 0] = 0;
		break;
	}
	return eh.e_type;
}

/* The arguments as asked: what the shebangs put in front, then the caller's
 * after its argv[0]. */
struct hx_args {
	const char **head;
	size_t hn;
	char *const *tail;
	size_t n;
};

static const char *hx_arg(const struct hx_args *a, size_t i) {
	return i >= a->n ? 0 : i < a->hn ? a->head[i] : a->tail[i - a->hn];
}

/* The program a loader's arguments name, after its options. */
static const char *hx_loader_program(const struct hx_args *a) {
	for (size_t i = 1; i < a->n; i++) {
		const char *o = hx_arg(a, i);
		if (o[0] != '-' || o[1] != '-') return o;
		o += 2;
		if (!*o) return hx_arg(a, i + 1);
		if (!strcmp(o, "list") || !strncmp(o, "library-path=", 13) || !strncmp(o, "preload=", 8) ||
		    !strncmp(o, "argv0=", 6))
			continue;
		if (strcmp(o, "library-path") && strcmp(o, "preload") && strcmp(o, "argv0")) return 0;
		i++;
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

/* LD_PRELOAD with first, when not null, at its head, and no termux-exec
 * entry otherwise. Null when that leaves it empty. */
static char *hx_preload(char **area, const char *old, const char *first) {
	char *s = hx_put(area, "LD_PRELOAD=", first ? first : ""), *w = *area - 1;
	while (old && *old) {
		size_t n = strcspn(old, ": ");
		if (n && !hx_termux_exec(old, n)) {
			if (w > s + 11) *w++ = ':';
			memcpy(w, old, n);
			w += n;
		}
		old += n;
		if (*old) old++;
	}
	if (w == s + 11) {
		*area = s;
		return 0;
	}
	*w++ = 0;
	*area = w;
	return s;
}

/* The most hx_environ writes past the caller's entries: pointers and text. */
static size_t hx_environ_size(const struct hx_env *e, const char *old) {
	size_t n = 7 * sizeof(char *) + (e->pse ? 28 + strlen(e->pse) : 0) + 16 + strlen(hx_cfg.prefix) +
	           2 * (18 + strlen(hx_cfg.etc)) + (e->tmp != HX_TMP_KEEP ? strlen(hx_cfg.tmp) + 1 : 0);
	if (e->preload == HX_OURS) n += 13 + strlen(hx_cfg.preload) + (old ? strlen(old) : 0);
	if (e->preload == HX_STRIP) n += 12 + (old ? strlen(old) : 0);
	return n;
}

static char **hx_environ(char **out, char *area, char *const envp[], const struct hx_env *e, const char *old) {
	size_t k = 0;
	char *pre;
	int tmp = hx_cfg.tmp[0] && (e->tmp == HX_TMP_SET || (e->tmp == HX_TMP_ADD && !hx_env_get(envp, HX_TMP)));
	for (; *envp; envp++) {
		const char *v = *envp;
		if ((e->preload != HX_KEEP && hx_is(v, "LD_PRELOAD")) || (e->libpath == HX_DROP && hx_is(v, "LD_LIBRARY_PATH")) ||
		    hx_is(v, HX_PSE) || (hx_cfg.prefix[0] && hx_is(v, "HARNESS_PREFIX")) ||
		    (hx_cfg.etc[0] && hx_is(v, "HARNESS_ETC")) || (e->shim && hx_cfg.etc[0] && hx_is(v, "SYSCALL_SHIM_ETC")) ||
		    (tmp && hx_is(v, HX_TMP)))
			continue;
		out[k++] = (char *)v;
	}
	/* Ahead of the entries below: where it stays when routed again. */
	if (tmp) out[k++] = hx_cfg.tmp;
	if (e->preload == HX_OURS || e->preload == HX_STRIP) {
		pre = hx_preload(&area, old, e->preload == HX_OURS ? hx_cfg.preload : 0);
		if (pre) out[k++] = pre;
	}
	if (e->pse) out[k++] = hx_put(&area, HX_PSE "=", e->pse);
	if (hx_cfg.prefix[0]) out[k++] = hx_put(&area, "HARNESS_PREFIX=", hx_cfg.prefix);
	if (hx_cfg.etc[0]) out[k++] = hx_put(&area, "HARNESS_ETC=", hx_cfg.etc);
	if (e->shim && hx_cfg.etc[0]) out[k++] = hx_put(&area, "SYSCALL_SHIM_ETC=", hx_cfg.etc);
	out[k] = 0;
	return out;
}

/* The row for real, the target's realpath. A linker64 exec's argv[1] is
 * resolved against base, as the path is. */
static int hx_row(struct hx_scratch *s, const char *real, int native, int type, const struct hx_args *a,
                  const char *base) {
	const char *interp = s->elf_interp, *arg1 = hx_arg(a, 1);
	if (hx_is_linker(real)) {
		if (arg1 && hx_cfg.proot[0] &&
		    !(base && arg1[0] != '/' ? hx_join(s->arg1, base, arg1) : hx_copy(s->arg1, arg1)) &&
		    !hx_canon(s->arg1, s->arg1) && hx_is_proot(s, s->arg1))
			return 1;
		return 2;
	}
	if (hx_cfg.loader[0] && !strcmp(real, hx_cfg.loader)) return 3;
	if (hx_cfg.shim[0] && !strcmp(real, hx_cfg.shim)) return 4;
	if (hx_is_proot(s, real)) return 5;
	if (hx_under_system(real)) return native && interp[0] ? 6 : 7;
	if (hx_is_linker_interp(interp)) return 8;
	if (interp[0] && hx_is_musl(interp)) return 9;
	if (native && (type == ET_EXEC || type == ET_DYN) && hx_cfg.tmp[0] && hx_cfg.etc[0] && hx_cfg.proot[0]) return 10;
	return 11;
}

/* hx_route_once's answer for the shim under proot: decide its argv[1...]. */
#define HX_AGAIN (-1)

static int hx_route_once(const char *path, char *const argv[], char *const envp[], const char *base,
                         struct hx_exec *x) {
	static char *const none[] = {0};
	struct hx_scratch *s = &x->s;
	char *w = s->work, *p = s->path, **av, **ev;
	const char **head = s->head, *old, *head0, *prog, *pre[HX_PRE];
	struct hx_args a;
	struct hx_env env = {HX_KEEP, HX_KEEP, 0, 0, HX_TMP_KEEP};
	size_t argc = 0, envc = 0, hn = 0, pn = 0, from = 0, depth, i, k = 0, need;
	unsigned long pid;
	int native = 0, type = 0, row, shim = 0, first = 0, under = 0, loaded, e;

	x->map = 0;
	if (!path) return EFAULT;
	if (!argv) argv = none;
	if (!envp) envp = none;
	while (argv[argc])
		argc++;
	while (envp[envc])
		envc++;
	old = hx_env_get(envp, "LD_PRELOAD");

	if (argv[0]) head[hn++] = argv[0];
	e = base && path[0] != '/' ? hx_join(p, base, path) : hx_copy(p, path);
	if (e) return e;

	/* Step 0. */
	if (hx_cfg.self[0] && hx_proc_pid(p, &pid) && hx_own(pid) && !hx_link("/proc/self/exe", w) && hx_loaded(w))
		memcpy(p, hx_cfg.self, strlen(hx_cfg.self) + 1);

	for (depth = 0;; depth++) {
		char *h, *interp, *arg;
		long fd, n, r;
		size_t add, keep;

		/* Step 1. */
		hx_rewrite(p, depth ? HX_INTERP_PATH : PATH_MAX, w);
		r = hx_sys(SYS_faccessat, AT_FDCWD, p, X_OK, 0);
		if (r < 0) return (int)-r;

		/* A file that cannot be read is classified by its path alone. */
		s->elf_interp[0] = 0;
		fd = hx_sys(SYS_openat, AT_FDCWD, p, O_RDONLY | O_CLOEXEC);
		if (fd < 0) break;
		n = hx_sys(SYS_pread64, fd, w, HX_HEADER - 1, 0);
		if (n < 0) {
			hx_sys(SYS_close, fd);
			return n == -EISDIR ? EACCES : (int)-n;
		}
		w[n] = 0;
		if (n >= SELFMAG && !memcmp(w, ELFMAG, SELFMAG)) {
			type = hx_elf(fd, w, (size_t)n, &native, s->elf_interp);
			hx_sys(SYS_close, fd);
			break;
		}
		hx_sys(SYS_close, fd);

		/* Step 2. The line stays in header[depth], where the interpreter's
		 * argument and name are pointed at from head. */
		if (n < 2 || w[0] != '#' || w[1] != '!') return ENOEXEC;
		if (depth == HX_DEPTH) return ELOOP;
		h = s->header[depth];
		memcpy(h, w, (size_t)n + 1);
		if (hx_shebang(h, (size_t)n, &interp, &arg)) return ENOEXEC;
		/* A shebang line is shorter than HX_HEADER, so this fits. */
		memcpy(s->interp[depth], interp, strlen(interp) + 1);
		add = arg ? 3 : 2;
		keep = hn ? hn - 1 : 0;
		memmove(head + add, head + 1, keep * sizeof *head);
		head[0] = interp;
		if (arg) head[1] = arg;
		head[add - 1] = p;
		hn = add + keep;
		p = s->interp[depth];
	}

	/* Step 3. */
	if (hx_canon(p, w) && hx_normalize(p, w)) hx_copy(w, p);
	if (!hx_cfg.bin_proot[0] || hx_canon(hx_cfg.bin_proot, s->proot)) s->proot[0] = 0;
	a = (struct hx_args){head, hn, argv[0] ? argv + 1 : argv, hn + argc - (argv[0] ? 1 : 0)};
	row = hx_row(s, w, native, type, &a, base);
	loaded = row == 2 || hx_is_linker_interp(s->elf_interp) || (s->elf_interp[0] && hx_is_musl(s->elf_interp));
	if (row != 1 && row != 5) {
		/* w holds the tracer's executable, then this process's. */
		pid = hx_tracer(s, w);
		under = hx_proot_tracer(s, pid, w);
		if ((row == 3 || row == 9) && !under && hx_cfg.use_shim && hx_cfg.shim[0] && strcmp(w, hx_cfg.shim) &&
		    (hx_link("/proc/self/exe", w) || strcmp(w, hx_cfg.shim)))
			shim = 1;
	}
	/* Under proot, rows 3 and 9 run without the shim, and every other row as
	 * asked: row 0. Row 9 runs as asked where the musl loader is bound. */
	if (under) {
		if (row == 4 && hn == 1 && argv[1]) return HX_AGAIN;
		if (row != 3 && (row != 9 || !hx_sys(SYS_faccessat, AT_FDCWD, hx_musl_lib, F_OK, 0))) row = 0;
	}

	/* Where P goes in place of argv[0], it is normalized into w; head0 may
	 * point there before it is written. */
	head0 = hn ? head[0] : w;
	x->path = p;

	switch (row) {
	case 0:
		env = (struct hx_env){loaded ? HX_STRIP : HX_DROP, HX_KEEP, 0, 0, HX_TMP_KEEP};
		break;
	case 1:
		/* argv[1] is the proot's own argv[0]. */
		from = 1;
		/* fall through */
	case 5:
		env = (struct hx_env){HX_STRIP, HX_KEEP, 0, 0, HX_TMP_ADD};
		x->path = hx_cfg.proot;
		break;
	case 2:
		env = (struct hx_env){HX_OURS, HX_KEEP, 0, 0, HX_TMP_KEEP};
		if (hx_arg(&a, 1) && !hx_normalize(hx_arg(&a, 1), w)) env.pse = w;
		break;
	case 3:
		if (shim) {
			/* The shim runs the loader, which this router in the shim then
			 * decides again, without the shim. */
			env = (struct hx_env){HX_OURS, HX_KEEP, 1, 0, HX_TMP_KEEP};
			pre[pn++] = hx_cfg.shim;
			first = 1;
			x->path = hx_cfg.shim;
		} else {
			env = (struct hx_env){HX_DROP, HX_DROP, 0, 0, HX_TMP_KEEP};
			prog = hx_loader_program(&a);
			if (prog && !hx_normalize(prog, w)) env.pse = w;
		}
		break;
	case 4:
		env = (struct hx_env){HX_OURS, HX_KEEP, 1, 0, HX_TMP_KEEP};
		break;
	case 6:
		env = (struct hx_env){HX_OURS, HX_DROP, 0, 0, HX_TMP_KEEP};
		break;
	case 7:
		env = (struct hx_env){HX_DROP, HX_DROP, 0, 0, HX_TMP_KEEP};
		break;
	case 8:
		env = (struct hx_env){HX_OURS, HX_KEEP, 0, w, HX_TMP_KEEP};
		pre[pn++] = head0;
		first = 1;
		x->path = hx_linker64;
		break;
	case 9:
		if (shim) pre[pn++] = hx_cfg.shim;
		pre[pn++] = hx_cfg.loader;
		pre[pn++] = "--argv0";
		pre[pn++] = head0;
		first = 1;
		x->path = shim ? hx_cfg.shim : hx_cfg.loader;
		env = shim ? (struct hx_env){HX_OURS, HX_KEEP, 1, 0, HX_TMP_KEEP}
		           : (struct hx_env){HX_DROP, HX_DROP, 0, w, HX_TMP_KEEP};
		break;
	case 10: {
		/* The binding set with glibc's ld.so where it exists. */
		size_t n = hx_cfg.nbind - (size_t)hx_cfg.glibc_bind;
		if (hx_cfg.glibc_bind && !hx_sys(SYS_faccessat, AT_FDCWD, hx_cfg.glibc, F_OK, 0)) n++;
		pre[pn++] = hx_cfg.proot;
		pre[pn++] = "--exec-bin";
		pre[pn++] = hx_cfg.bin;
		for (i = 0; i < n; i++)
			pre[pn++] = hx_cfg.bind[i];
		first = 1;
		x->path = hx_cfg.proot;
		/* A bionic preload fails in another ld.so. */
		env = s->elf_interp[0] ? (struct hx_env){HX_DROP, HX_DROP, 0, 0, HX_TMP_SET}
		                       : (struct hx_env){HX_STRIP, HX_KEEP, 0, 0, HX_TMP_SET};
		break;
	}
	}
	if (first && hx_normalize(p, w)) hx_copy(w, p);

	need = (pn + a.n + 2) * sizeof(char *);
	if (row != 11) need += envc * sizeof(char *) + hx_environ_size(&env, old);
	if (need <= sizeof x->area) {
		av = x->area;
	} else {
		long m = hx_sys(SYS_mmap, 0, need, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
		if (m < 0 && m > -4096) return E2BIG;
		x->map = (void *)m;
		x->len = need;
		av = x->map;
	}
	for (i = 0; i < pn; i++)
		av[k++] = (char *)pre[i];
	if (first) av[k++] = w;
	for (i = (size_t)first + from; i < a.n; i++)
		av[k++] = (char *)hx_arg(&a, i);
	av[k++] = 0;
	x->argv = av;
	if (row == 11) {
		x->envp = (char **)envp;
		return 0;
	}
	ev = av + k;
	x->envp = hx_environ(ev, (char *)(ev + envc + 7), envp, &env, old);
	return 0;
}

int hx_route(const char *path, char *const argv[], char *const envp[], const char *base, struct hx_exec *x) {
	int e;
	while ((e = hx_route_once(path, argv, envp, base, x)) == HX_AGAIN)
		path = *++argv;
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
	if (!path || !hx_proc_pid(path, &pid) || hx_link(path, buf) || !hx_loaded(buf)) return 0;
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

/* The file mapped at addr, from /proc/self/maps, into out. */
static int hx_mapped(uintptr_t addr, char *out) {
	char *b = hx_cfg.maps, *line, *nl;
	size_t len = 0;
	int skip = 0, e = ENOENT;
	long fd = hx_sys(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC), n;
	if (fd < 0) return (int)-fd;
	while ((n = hx_sys(SYS_read, fd, b + len, sizeof hx_cfg.maps - 1 - len)) > 0) {
		len += (size_t)n;
		b[len] = 0;
		for (line = b; (nl = memchr(line, '\n', (size_t)(b + len - line))); line = nl + 1) {
			uintptr_t lo = 0, hi = 0;
			const char *c = line;
			*nl = 0;
			if (skip) {
				skip = 0;
				continue;
			}
			/* start-end perms offset dev inode path */
			for (; (*c >= '0' && *c <= '9') || (*c >= 'a' && *c <= 'f'); c++)
				lo = lo * 16 + (uintptr_t)(*c <= '9' ? *c - '0' : *c - 'a' + 10);
			if (*c++ != '-') continue;
			for (; (*c >= '0' && *c <= '9') || (*c >= 'a' && *c <= 'f'); c++)
				hi = hi * 16 + (uintptr_t)(*c <= '9' ? *c - '0' : *c - 'a' + 10);
			if (addr < lo || addr >= hi) continue;
			c = strchr(c, '/');
			e = c ? hx_copy(out, c) : ENOENT;
			goto done;
		}
		len = (size_t)(b + len - line);
		memmove(b, line, len);
		/* A line longer than the buffer is passed over. */
		if (len == sizeof hx_cfg.maps - 1) {
			len = 0;
			skip = 1;
		}
	}
done:
	hx_sys(SYS_close, fd);
	return e;
}

/* Adds --bind=src:dst to row 10's arguments. */
static int hx_bind(const char *src, const char *dst) {
	size_t ls = strlen(src), ld = strlen(dst);
	char *b;
	if (hx_cfg.nbind == HX_BINDS || 7 + ls + 1 + ld >= PATH_MAX) return ENOSPC;
	b = hx_cfg.bind_text[hx_cfg.nbind];
	memcpy(b, "--bind=", 7);
	memcpy(b + 7, src, ls);
	b[7 + ls] = ':';
	memcpy(b + 8 + ls, dst, ld + 1);
	hx_cfg.bind[hx_cfg.nbind++] = b;
	return 0;
}

struct hx_dirent {
	uint64_t ino;
	int64_t off;
	unsigned short reclen;
	unsigned char type;
	char name[];
};

/* Binds every file in $HARNESS_ETC onto /etc, in name order, leaving the last
 * three places for the rest. */
static void hx_bind_etc(void) {
	static const char more[] = "harness-exec: more files in $HARNESS_ETC than a proot binds; the rest are left out\n";
	char buf[1024], src[PATH_MAX], dst[5 + NAME_MAX + 1];
	size_t first = hx_cfg.nbind, i, j;
	int over = 0;
	long fd = hx_sys(SYS_openat, AT_FDCWD, hx_cfg.etc, O_RDONLY | O_DIRECTORY | O_CLOEXEC), n;
	if (fd < 0) return;
	while ((n = hx_sys(SYS_getdents64, fd, buf, sizeof buf)) > 0)
		for (long off = 0; off < n; off += ((struct hx_dirent *)(buf + off))->reclen) {
			struct hx_dirent *d = (struct hx_dirent *)(buf + off);
			if (d->type == DT_DIR || !strcmp(d->name, ".") || !strcmp(d->name, "..")) continue;
			if (hx_cfg.nbind - first == HX_BINDS - 3) {
				over = 1;
				continue;
			}
			memcpy(dst, "/etc/", 5);
			memcpy(dst + 5, d->name, strlen(d->name) + 1);
			if (!hx_join(src, hx_cfg.etc, d->name)) hx_bind(src, dst);
		}
	hx_sys(SYS_close, fd);
	for (i = first + 1; i < hx_cfg.nbind; i++)
		for (j = i; j > first && strcmp(hx_cfg.bind[j - 1], hx_cfg.bind[j]) > 0; j--) {
			const char *t = hx_cfg.bind[j];
			hx_cfg.bind[j] = hx_cfg.bind[j - 1];
			hx_cfg.bind[j - 1] = t;
		}
	if (over) hx_sys(SYS_write, 2, more, sizeof more - 1);
}

/* Row 10's bindings, of the sources that exist; glibc's ld.so last, checked
 * at each route. */
static void hx_bindings(void) {
	char src[PATH_MAX];
	hx_cfg.nbind = 0;
	hx_cfg.glibc_bind = 0;
	if (hx_cfg.etc[0]) hx_bind_etc();
	if (hx_cfg.prefix[0] && !hx_join(src, hx_cfg.prefix, "etc/tls/cert.pem") &&
	    !hx_sys(SYS_faccessat, AT_FDCWD, src, F_OK, 0))
		hx_bind(src, "/etc/ssl/certs/ca-certificates.crt");
	if (hx_cfg.loader[0] && !hx_sys(SYS_faccessat, AT_FDCWD, hx_cfg.loader, F_OK, 0)) hx_bind(hx_cfg.loader, HX_MUSL_LIB);
	if (hx_cfg.prefix[0] && !hx_join(hx_cfg.glibc, hx_cfg.prefix, "glibc/lib/" HX_GLIBC_NAME))
		hx_cfg.glibc_bind = !hx_bind(hx_cfg.glibc, HX_GLIBC_LIB);
}

void hx_capture(char *const envp[], const char *libdir) {
	char dir[PATH_MAX];
	hx_take(hx_cfg.prefix, envp, "HARNESS_PREFIX");
	hx_take(hx_cfg.etc, envp, "HARNESS_ETC");
	hx_take(hx_cfg.self, envp, HX_PSE);
	/* The loader's directory, from the mapping that holds this code: under
	 * proot, /proc/self/exe names the program. */
	if (!libdir) {
		char *slash;
		if (hx_mapped((uintptr_t)hx_capture, dir)) dir[0] = 0;
		slash = strrchr(dir, '/');
		if (slash) *slash = 0;
		libdir = dir;
	}
	if (!libdir[0] || (hx_canon(libdir, hx_cfg.libdir) && hx_copy(hx_cfg.libdir, libdir))) hx_cfg.libdir[0] = 0;
	if (!hx_cfg.libdir[0] || hx_join(hx_cfg.loader, hx_cfg.libdir, HX_LOADER) ||
	    hx_join(hx_cfg.shim, hx_cfg.libdir, HX_SHIM) || hx_join(hx_cfg.preload, hx_cfg.libdir, HX_PRELOAD))
		hx_cfg.loader[0] = hx_cfg.shim[0] = hx_cfg.preload[0] = 0;
	if (!hx_cfg.libdir[0] || hx_join(hx_cfg.proot, hx_cfg.libdir, HX_PROOT) ||
	    hx_sys(SYS_faccessat, AT_FDCWD, hx_cfg.proot, X_OK, 0))
		hx_cfg.proot[0] = 0;
	for (size_t i = 0; i < sizeof hx_linkers / sizeof *hx_linkers; i++)
		if (hx_canon(hx_linkers[i], hx_cfg.linker[i])) hx_cfg.linker[i][0] = 0;
	hx_cfg.use_shim = HX_USE_SHIM;
	hx_cfg.bin[0] = hx_cfg.bin_proot[0] = hx_cfg.tmp[0] = 0;
	if (hx_cfg.prefix[0] && !hx_join(hx_cfg.bin, hx_cfg.prefix, "bin") &&
	    !hx_join(hx_cfg.bin_proot, hx_cfg.bin, "proot") && strlen(hx_cfg.prefix) + 18 < PATH_MAX) {
		char *t = hx_cfg.tmp;
		hx_put(&t, HX_TMP "=", hx_cfg.prefix);
		memcpy(t - 1, "/tmp", 5);
	} else {
		hx_cfg.bin[0] = hx_cfg.bin_proot[0] = 0;
	}
	hx_bindings();
}
