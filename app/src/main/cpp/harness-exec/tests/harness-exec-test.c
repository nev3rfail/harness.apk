/*
 * Host unit tests of the router, against the host libc. The Android paths the
 * router knows (/system, /apex, linker64) are fixtures under a temporary
 * sysroot, which the realpath hook strips, so a fixture at
 * <root>/sysroot/system/bin/sh classifies as /system/bin/sh.
 *
 * Run with tests/run.sh.
 */
#define UNIT_TEST 1
#include "../harness-exec.c"

#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>

/* The allocator the router must never reach while a test forbids it. */
extern void *__libc_malloc(size_t);
extern void *__libc_calloc(size_t, size_t);
extern void *__libc_realloc(void *, size_t);
extern void __libc_free(void *);
static volatile int forbid_alloc;
static void alloc_trap(void) {
	static const char msg[] = "allocator called while forbidden\n";
	if (write(2, msg, sizeof msg - 1) < 0) _exit(98);
	_exit(99);
}
void *malloc(size_t n) {
	if (forbid_alloc) alloc_trap();
	return __libc_malloc(n);
}
void *calloc(size_t a, size_t b) {
	if (forbid_alloc) alloc_trap();
	return __libc_calloc(a, b);
}
void *realloc(void *p, size_t n) {
	if (forbid_alloc) alloc_trap();
	return __libc_realloc(p, n);
}
void free(void *p) {
	if (forbid_alloc) alloc_trap();
	__libc_free(p);
}

static int failures, checks;
#define CHECK(c)                                                                                                       \
	do {                                                                                                               \
		checks++;                                                                                                      \
		if (!(c)) {                                                                                                    \
			fprintf(stderr, "%s:%d: CHECK(%s)\n", __FILE__, __LINE__, #c);                                             \
			failures++;                                                                                                \
		}                                                                                                              \
	} while (0)

static char *S(const char *fmt, ...) {
	va_list ap;
	char *s;
	va_start(ap, fmt);
	if (vasprintf(&s, fmt, ap) < 0) abort();
	va_end(ap);
	return s;
}

/* --- fixtures --- */

static char R[PATH_MAX], L[PATH_MAX], PX[PATH_MAX], SR[PATH_MAX], D[PATH_MAX], ETC[PATH_MAX];
static char LOADER[PATH_MAX], SHIM[PATH_MAX], OURS[PATH_MAX], LINKER[PATH_MAX], PROOT[PATH_MAX];
static char BINPROOT[PATH_MAX], GLIBC[PATH_MAX], MUSLLIB[PATH_MAX];

static void put(const char *path, const void *data, size_t n, mode_t mode) {
	FILE *f = fopen(path, "wb");
	if (!f || fwrite(data, 1, n, f) != n || fclose(f)) {
		perror(path);
		exit(2);
	}
	chmod(path, mode);
}

static void script(const char *path, const char *text) { put(path, text, strlen(text), 0755); }

/* An ELF header of type type, a PT_PHDR and either a PT_INTERP naming interp or
 * a PT_LOAD. */
static void elf_typed(const char *path, int type, int class64, int machine, const char *interp) {
	unsigned char b[512] = {0};
	Elf64_Ehdr *h = (Elf64_Ehdr *)b;
	Elf64_Phdr *ph = (Elf64_Phdr *)(b + sizeof *h);
	memcpy(h->e_ident, ELFMAG, SELFMAG);
	h->e_ident[EI_CLASS] = class64 ? ELFCLASS64 : ELFCLASS32;
	h->e_ident[EI_DATA] = ELFDATA2LSB;
	h->e_ident[EI_VERSION] = EV_CURRENT;
	h->e_type = (Elf64_Half)type;
	h->e_machine = (Elf64_Half)machine;
	h->e_version = EV_CURRENT;
	h->e_phoff = sizeof *h;
	h->e_ehsize = sizeof *h;
	h->e_phentsize = sizeof *ph;
	h->e_phnum = 2;
	ph[0].p_type = PT_PHDR;
	if (interp) {
		ph[1].p_type = PT_INTERP;
		ph[1].p_offset = 300;
		ph[1].p_filesz = strlen(interp) + 1;
		strcpy((char *)b + 300, interp);
	} else {
		ph[1].p_type = PT_LOAD;
	}
	put(path, b, sizeof b, 0755);
}

static void elf(const char *path, int class64, int machine, const char *interp) {
	elf_typed(path, ET_DYN, class64, machine, interp);
}

static void mkdirs(const char *path) {
	char b[PATH_MAX];
	strcpy(b, path);
	for (char *p = b + 1; *p; p++)
		if (*p == '/') {
			*p = 0;
			mkdir(b, 0755);
			*p = '/';
		}
	mkdir(b, 0755);
}

/* --- hooks --- */

static const char *t_self;   /* /proc/self/exe, or null for the real one */
static const char *t_other;  /* /proc/<other pid>/exe, or null for the real one */
static const char *t_traced; /* the tracer's executable, or null */
static pid_t t_tracer_pid;   /* the tracer's pid, when its /proc entry is read */

static int t_link(const char *path, char *out) {
	unsigned long pid;
	if (hx_proc_pid(path, &pid)) {
		if (hx_own(pid) && t_self) return hx_copy(out, t_self);
		if (!hx_own(pid) && t_other) return hx_copy(out, t_other);
	}
	return hx_link_sys(path, out);
}

static int t_canon(const char *path, char *out) {
	size_t n = strlen(SR);
	int e = hx_canon_sys(path, out);
	if (!e && !strncmp(out, SR, n) && out[n] == '/') memmove(out, out + n, strlen(out + n) + 1);
	return e;
}

static unsigned long t_tracer(struct hx_scratch *s, char *out) {
	(void)s;
	strcpy(out, t_traced ? t_traced : "");
	return !t_traced ? 0 : t_tracer_pid ? (unsigned long)t_tracer_pid : 1;
}

static char *CAPTURE[4];

static void configure(int use_shim) {
	hx_capture(CAPTURE, L);
	/* What capture finds on a device. */
	strcpy(hx_cfg.linker[0], "/apex/com.android.runtime/bin/linker64");
	strcpy(hx_cfg.linker[1], "/apex/com.android.runtime/bin/linker64");
	strcpy(hx_cfg.linker[2], "/system/bin/bootstrap/linker64");
	hx_cfg.use_shim = use_shim;
	t_self = "/system/bin/sh";
	t_other = t_traced = NULL;
	t_tracer_pid = 0;
}

/* A process whose cmdline is argv, standing in cwd, for the tracer test to
 * read: this program again, which HX_HOLD keeps waiting. */
static pid_t hold(const char *cwd, char *const argv[]) {
	int p[2];
	char c;
	pid_t pid;
	if (pipe2(p, O_CLOEXEC)) abort();
	pid = fork();
	if (!pid) {
		if (chdir(cwd)) _exit(127);
		execve("/proc/self/exe", argv, (char *[]){"HX_HOLD=1", NULL});
		_exit(127);
	}
	close(p[1]);
	if (read(p[0], &c, 1) != 0) abort();
	close(p[0]);
	return pid;
}

static void release(pid_t pid) {
	kill(pid, SIGKILL);
	waitpid(pid, NULL, 0);
}

static void setup(void) {
	strcpy(R, "/tmp/harness-exec-XXXXXX");
	if (!mkdtemp(R)) abort();
	strcpy(L, S("%s/lib", R));
	strcpy(PX, S("%s/prefix", R));
	strcpy(SR, S("%s/sysroot", R));
	strcpy(D, S("%s/data", R));
	strcpy(ETC, S("%s/etc", R));
	strcpy(LOADER, S("%s/libmuslloader.so", L));
	strcpy(SHIM, S("%s/libsyscallshim.so", L));
	strcpy(OURS, S("%s/libtermuxexec.so", L));
	strcpy(LINKER, S("%s/system/bin/linker64", SR));
	strcpy(PROOT, S("%s/libproot.so", L));
	strcpy(BINPROOT, S("%s/bin/proot", PX));
	strcpy(GLIBC, S("%s/glibc/lib/%s", PX, HX_GLIBC_NAME));
	strcpy(MUSLLIB, S("%s/ld-musl-bound", R));
	mkdirs(L);
	mkdirs(S("%s/bin", PX));
	mkdirs(S("%s/tmp", PX));
	mkdirs(S("%s/etc/tls", PX));
	mkdirs(S("%s/glibc/lib", PX));
	mkdirs(S("%s/apex/com.android.runtime/bin", SR));
	mkdirs(S("%s/system/bin/bootstrap", SR));
	mkdirs(D);
	mkdirs(ETC);
	mkdirs(S("%s/dir", D));

	elf(LOADER, 1, HX_MACHINE, NULL);
	elf(SHIM, 1, HX_MACHINE, "/system/bin/linker64");
	elf(OURS, 1, HX_MACHINE, NULL);
	elf(S("%s/bin/hxtool", PX), 1, HX_MACHINE, "/system/bin/linker64");
	elf(S("%s/bin/sh", PX), 1, HX_MACHINE, "/system/bin/linker64");
	/* libproot.so is a bionic PIE. termux.net's proot at the prefix path is
	 * too, or the real one named by HX_REAL_PROOT. */
	elf(PROOT, 1, HX_MACHINE, "/system/bin/linker64");
	if (getenv("HX_REAL_PROOT")) {
		if (system(S("cp '%s' '%s'", getenv("HX_REAL_PROOT"), BINPROOT))) abort();
	} else {
		elf(BINPROOT, 1, HX_MACHINE, "/system/bin/linker64");
	}
	script(S("%s/etc/tls/cert.pem", PX), "pem\n");
	script(S("%s/hosts", ETC), "127.0.0.1 localhost\n");
	script(S("%s/resolv.conf", ETC), "nameserver 1.1.1.1\n");
	mkdirs(S("%s/sub", ETC));

	elf(S("%s/apex/com.android.runtime/bin/linker64", SR), 1, HX_MACHINE, NULL);
	if (symlink("../../apex/com.android.runtime/bin/linker64", LINKER)) abort();
	elf(S("%s/system/bin/bootstrap/linker64", SR), 1, HX_MACHINE, NULL);
	elf(S("%s/system/bin/linker", SR), 0, EM_386, NULL);
	elf(S("%s/system/bin/sh", SR), 1, HX_MACHINE, "/system/bin/linker64");
	elf(S("%s/system/bin/static", SR), 1, HX_MACHINE, NULL);
	elf(S("%s/system/bin/nonnative", SR), 1, EM_RISCV, "/system/bin/linker64");

	elf(S("%s/bionic", D), 1, HX_MACHINE, "/system/bin/linker64");
	elf(S("%s/musl", D), 1, HX_MACHINE, "/lib/ld-musl-x86_64.so.1");
	elf(S("%s/static", D), 1, HX_MACHINE, NULL);
	elf(S("%s/foreign", D), 1, HX_MACHINE, "/lib64/ld-linux-x86-64.so.2");
	elf(S("%s/nonnative", D), 1, EM_RISCV, NULL);
	elf_typed(S("%s/exec", D), ET_EXEC, 1, HX_MACHINE, NULL);
	elf_typed(S("%s/rel", D), ET_REL, 1, HX_MACHINE, NULL);
	elf(S("%s/glibc", D), 1, HX_MACHINE, GLIBC);
	elf(S("%s/ldlinux", D), 1, HX_MACHINE, "/lib/ld-linux-aarch64.so.1");
	elf(S("%s/nix", D), 1, HX_MACHINE, "/nix/store/x/lib/ld.so");
	elf(S("%s/apexbionic", D), 1, HX_MACHINE, "/apex/com.android.runtime/bin/linker64");
	elf(S("%s/bootbionic", D), 1, HX_MACHINE, "/system/bin/bootstrap/linker64");
	elf(S("%s/noexec", D), 1, HX_MACHINE, "/system/bin/linker64");
	chmod(S("%s/noexec", D), 0644);
	if (symlink("bionic", S("%s/alias", D))) abort();

	script(S("%s/tool.sh", D), "#!/usr/bin/hxtool -x\necho\n");
	script(S("%s/tabs.sh", D), S("#!\t%s/musl\t-a\tb\t\n", D));
	script(S("%s/spaces.sh", D), S("#!   %s/bionic   arg with  spaces  \n", D));
	script(S("%s/noarg.sh", D), S("#!%s/bionic  \n", D));
	script(S("%s/s0", D), S("#!%s/s1\n", D));
	script(S("%s/s1", D), S("#!%s/s2 a1\n", D));
	script(S("%s/s2", D), S("#!%s/s3\n", D));
	script(S("%s/s3", D), S("#!%s/s4\n", D));
	script(S("%s/s4", D), S("#!%s/bionic\n", D));
	script(S("%s/text", D), "hello\n");
	script(S("%s/blank.sh", D), "#!  \n");
	script(S("%s/empty", D), "");

	CAPTURE[0] = S("HARNESS_PREFIX=%s", PX);
	CAPTURE[1] = S("HARNESS_ETC=%s", ETC);
	CAPTURE[2] = S("TERMUX_EXEC__PROC_SELF_EXE=%s/musl", D);
	CAPTURE[3] = NULL;
	if (chdir(R)) abort();
	hx_link = t_link;
	hx_canon = t_canon;
	hx_tracer = t_tracer;
	hx_linker64 = LINKER;
	hx_musl_lib = MUSLLIB;
}

/* --- expectations --- */

#define V(...) ((const char *const[]){__VA_ARGS__, NULL})

static char *ENV[] = {
    "PATH=/usr/bin",
    "LD_PRELOAD=/old/libtermux-exec-ld-preload.so:/o/other.so",
    "LD_LIBRARY_PATH=/l",
    "HARNESS_PREFIX=/stale",
    "TERMUX_EXEC__PROC_SELF_EXE=/stale",
    "SYSCALL_SHIM_ETC=/stale-etc",
    NULL,
};

static int same(char *const *a, const char *const *b) {
	for (; *a && *b; a++, b++)
		if (strcmp(*a, *b)) return 0;
	return !*a && !*b;
}

static void dump(const char *what, char *const *a) {
	fprintf(stderr, "    %s:", what);
	for (; *a; a++)
		fprintf(stderr, " [%s]", *a);
	fprintf(stderr, "\n");
}

static int matches(int line, const struct hx_exec *x, const char *path, const char *const *argv,
                   const char *const *envp) {
	if (!strcmp(x->path, path) && same(x->argv, argv) && same(x->envp, envp)) return 1;
	fprintf(stderr, "line %d: routed to %s\n", line, x->path);
	dump("argv", x->argv);
	dump("envp", x->envp);
	fprintf(stderr, "  expected %s\n", path);
	dump("argv", (char *const *)argv);
	dump("envp", (char *const *)envp);
	return 0;
}

/* Routes, compares, and routes the result again, which must change nothing. */
static void expect_at(int line, const char *path, char *const *argv, char *const *envp, const char *xpath,
                      const char *const *xargv, const char *const *xenv) {
	struct hx_exec x, y;
	int e = hx_route(path, argv, envp, NULL, &x);
	checks++;
	if (e) {
		fprintf(stderr, "line %d: routing %s: %s\n", line, path, strerror(e));
		failures++;
		return;
	}
	if (!matches(line, &x, xpath, xargv, xenv)) {
		failures++;
	} else if ((e = hx_route(x.path, x.argv, x.envp, NULL, &y))) {
		fprintf(stderr, "line %d: routing the routed %s: %s\n", line, x.path, strerror(e));
		failures++;
	} else {
		if (!matches(line, &y, xpath, xargv, xenv)) {
			fprintf(stderr, "line %d: routing the routed call changed it\n", line);
			failures++;
		}
		hx_release(&y);
	}
	hx_release(&x);
}
#define expect(...) expect_at(__LINE__, __VA_ARGS__)

static void expect_error(int line, const char *path, char *const *argv, int error) {
	struct hx_exec x;
	int e = hx_route(path, argv, ENV, NULL, &x);
	checks++;
	if (e != error) {
		fprintf(stderr, "line %d: %s: expected %s, got %s\n", line, path, strerror(error), e ? strerror(e) : "success");
		failures++;
	}
	if (!e) hx_release(&x);
}
#define expect_err(...) expect_error(__LINE__, __VA_ARGS__)

static const char *PRE, *HP, *HE, *SE, *OTHER, *TMP, *EXECBIN;
/* Row 10's bindings, as capture composes them from the fixtures. */
static const char *B_HOSTS, *B_RESOLV, *B_CERT, *B_MUSL, *B_GLIBC;

/* --- tests --- */

static const char *loader_program(const char **v) {
	size_t n = 0;
	while (v[n])
		n++;
	return hx_loader_program(&(struct hx_args){v, n, NULL, n});
}

static void test_units(void) {
	char b[HX_HEADER], *interp, *arg, out[PATH_MAX];

	strcpy(b, "#!/bin/sh\n");
	CHECK(!hx_shebang(b, strlen(b), &interp, &arg) && !strcmp(interp, "/bin/sh") && !arg);
	strcpy(b, "#! /bin/sh -x \n");
	CHECK(!hx_shebang(b, strlen(b), &interp, &arg) && !strcmp(interp, "/bin/sh") && !strcmp(arg, "-x"));
	strcpy(b, "#!/usr/bin/env\t-S  a b\t\nrest");
	CHECK(!hx_shebang(b, strlen(b), &interp, &arg) && !strcmp(interp, "/usr/bin/env") && !strcmp(arg, "-S  a b"));
	strcpy(b, "#!/bin/sh");
	CHECK(!hx_shebang(b, strlen(b), &interp, &arg) && !strcmp(interp, "/bin/sh") && !arg);
	strcpy(b, "#! \t\n");
	CHECK(hx_shebang(b, strlen(b), &interp, &arg) == ENOEXEC);

	strcpy(out, "/usr/bin/hxtool");
	hx_rewrite(out, sizeof out, (char[PATH_MAX]){0});
	CHECK(!strcmp(out, S("%s/bin/hxtool", PX)));
	strcpy(out, "/bin/sh");
	hx_rewrite(out, sizeof out, (char[PATH_MAX]){0});
	CHECK(!strcmp(out, S("%s/bin/sh", PX)));
	strcpy(out, "/usr/bin/hx-absent");
	hx_rewrite(out, sizeof out, (char[PATH_MAX]){0});
	CHECK(!strcmp(out, "/usr/bin/hx-absent"));
	for (const char *const *p = V("/system/bin/sh", "/usr/local/bin/hxtool", "/binx/hxtool", "bin/hxtool"); *p; p++) {
		strcpy(out, *p);
		hx_rewrite(out, sizeof out, (char[PATH_MAX]){0});
		CHECK(!strcmp(out, *p));
	}

	CHECK(!hx_normalize("/a/./b/../c//d/", out) && !strcmp(out, "/a/c/d"));
	CHECK(!hx_normalize("/../..", out) && !strcmp(out, "/"));
	CHECK(!hx_normalize("rel/./x", out) && !strcmp(out, S("%s/rel/x", R)));

	CHECK(!strcmp(loader_program((const char *[]){"ld", "--preload", "x", "--argv0=a", "--library-path", "y", "p", "q", NULL}),
	              "p"));
	CHECK(!strcmp(loader_program((const char *[]){"ld", "--list", "--", "--weird", NULL}), "--weird"));
	CHECK(!loader_program((const char *[]){"ld", "--bogus", "p", NULL}));
	CHECK(!loader_program((const char *[]){"ld", "--preload", NULL}));

	{
		char area[2 * PATH_MAX], *a = area;
		const char *old = "/x/libtermux-exec.so /y/a.so::/old/lib/x86_64/libtermuxexec.so:/z/b.so";
		CHECK(!strcmp(hx_preload(&a, old, OURS), S("LD_PRELOAD=%s:/y/a.so:/z/b.so", OURS)));
		a = area;
		CHECK(!strcmp(hx_preload(&a, NULL, OURS), S("LD_PRELOAD=%s", OURS)));
		/* Termux-exec entries removed, and the variable with them when
		 * nothing is left. */
		a = area;
		CHECK(!strcmp(hx_preload(&a, old, NULL), "LD_PRELOAD=/y/a.so:/z/b.so"));
		a = area;
		CHECK(!hx_preload(&a, "/x/libtermux-exec.so:/old/libtermuxexec.so", NULL) && a == area);
		CHECK(!hx_preload(&a, NULL, NULL) && a == area);
	}

	/* Classification of each kind of ELF by its header. */
	{
		int native;
		char interp_buf[HX_HEADER];
		struct {
			const char *path;
			int native, type;
			const char *interp;
		} cases[] = {
		    {S("%s/bionic", D), 1, ET_DYN, "/system/bin/linker64"},
		    {S("%s/musl", D), 1, ET_DYN, "/lib/ld-musl-x86_64.so.1"},
		    {S("%s/static", D), 1, ET_DYN, ""},
		    {S("%s/exec", D), 1, ET_EXEC, ""},
		    {S("%s/rel", D), 1, ET_REL, ""},
		    {S("%s/foreign", D), 1, ET_DYN, "/lib64/ld-linux-x86-64.so.2"},
		    {S("%s/nonnative", D), 0, ET_DYN, ""},
		    {S("%s/system/bin/linker", SR), 0, 0, ""},
		};
		for (size_t i = 0; i < sizeof cases / sizeof *cases; i++) {
			int fd = open(cases[i].path, O_RDONLY), type;
			ssize_t n = read(fd, b, sizeof b - 1);
			type = hx_elf(fd, b, (size_t)n, &native, interp_buf);
			close(fd);
			CHECK(native == cases[i].native && type == cases[i].type && !strcmp(interp_buf, cases[i].interp));
		}
		CHECK(hx_is_musl("/lib/ld-musl-aarch64.so.1") && !hx_is_musl("/lib/ld-linux.so") && hx_is_musl("ld-musl-x"));
	}

	CHECK(!strcmp(hx_etc_path("/etc/hosts", out), S("%s/hosts", ETC)));
	CHECK(!strcmp(hx_etc_path("/etcx", out), "/etcx"));
	CHECK(!strcmp(hx_etc_path("/usr/etc/hosts", out), "/usr/etc/hosts"));
	hx_cfg.etc[0] = 0;
	setenv("HARNESS_ETC", "/from-env", 1);
	CHECK(!strcmp(hx_etc_path("/etc/resolv.conf", out), "/from-env/resolv.conf"));
	unsetenv("HARNESS_ETC");
	CHECK(!strcmp(hx_etc_path("/etc/resolv.conf", out), "/etc/resolv.conf"));
	configure(0);

	{
		char dir[PATH_MAX] = "";
		int fd = open(D, O_RDONLY | O_DIRECTORY);
		CHECK(!hx_chdir(dir, "sub", -1) && !strcmp(dir, S("%s/sub", R)));
		CHECK(!hx_chdir(dir, "x", -1) && !strcmp(dir, S("%s/sub/x", R)));
		CHECK(!hx_chdir(dir, "/abs", -1) && !strcmp(dir, "/abs"));
		CHECK(!hx_chdir(dir, NULL, fd) && !strcmp(dir, D));
		close(fd);
	}
}

/* Row 5's environment, and row 10's for a target without PT_INTERP. */
#define ROW5_ENV V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", TMP, OTHER, HP, HE)
/* Row 10's arguments in front of P, without and with glibc's ld.so. */
#define ROW10(...) V(PROOT, "--exec-bin", EXECBIN, B_HOSTS, B_RESOLV, B_CERT, B_MUSL, __VA_ARGS__)
#define ROW10G(...) V(PROOT, "--exec-bin", EXECBIN, B_HOSTS, B_RESOLV, B_CERT, B_MUSL, B_GLIBC, __VA_ARGS__)

static void test_rows(void) {
	char *bionic = S("%s/bionic", D), *musl = S("%s/musl", D);
	char *tmp_env[] = {ENV[0], ENV[1], ENV[2], ENV[3], ENV[4], ENV[5], "PROOT_TMP_DIR=/mine", NULL};
	configure(0);

	/* 1: linker64 running a proot, by the prefix path or libproot.so. */
	expect(LINKER, (char *[]){"linker64", BINPROOT, "-r", "/x", NULL}, ENV, PROOT, V(BINPROOT, "-r", "/x"), ROW5_ENV);
	expect(LINKER, (char *[]){"linker64", PROOT, "-0", NULL}, ENV, PROOT, V(PROOT, "-0"), ROW5_ENV);
	/* argv[1] resolved against a spawn's directory. */
	{
		struct hx_exec x;
		CHECK(!hx_route(LINKER, (char *[]){"l", "proot", "a", NULL}, ENV, S("%s/bin", PX), &x));
		CHECK(matches(__LINE__, &x, PROOT, V("proot", "a"), ROW5_ENV));
		hx_release(&x);
	}

	/* 5: a proot, by the prefix path, by libproot.so, and through a link. */
	expect(BINPROOT, (char *[]){"proot", "-r", "/x", "sh", NULL}, ENV, PROOT, V("proot", "-r", "/x", "sh"), ROW5_ENV);
	expect(PROOT, (char *[]){"p", NULL}, ENV, PROOT, V("p"), ROW5_ENV);
	if (symlink(PROOT, S("%s/bin/proot-link", PX))) abort();
	expect(S("%s/bin/proot-link", PX), (char *[]){"p", NULL}, ENV, PROOT, V("p"), ROW5_ENV);
	/* The caller's PROOT_TMP_DIR is kept. */
	expect(BINPROOT, (char *[]){"proot", NULL}, tmp_env, PROOT, V("proot"),
	       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", "PROOT_TMP_DIR=/mine", OTHER, HP, HE));

	/* 10: every other native program in the data directory, under proot. */
	for (int glibc = 0; glibc < 2; glibc++) {
		if (glibc) script(GLIBC, "");
		for (const char *const *p = V("exec", "static"); *p; p++) {
			const char *path = S("%s/%s", D, *p);
			expect(path, (char *[]){"x", "y", NULL}, ENV, PROOT, glibc ? ROW10G(path, "y") : ROW10(path, "y"), ROW5_ENV);
		}
		for (const char *const *p = V("glibc", "ldlinux", "foreign", "nix"); *p; p++) {
			const char *path = S("%s/%s", D, *p);
			expect(path, (char *[]){"x", "y", NULL}, ENV, PROOT, glibc ? ROW10G(path, "y") : ROW10(path, "y"),
			       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", TMP, HP, HE));
		}
		if (glibc) unlink(GLIBC);
	}
	/* A relative target, absolute and first after the options, with no --. */
	if (chdir(D)) abort();
	expect("./exec", (char *[]){"x", "a", NULL}, ENV, PROOT, ROW10(S("%s/exec", D), "a"), ROW5_ENV);
	if (chdir(R)) abort();
	/* The caller's PROOT_TMP_DIR is replaced. */
	expect(S("%s/static", D), (char *[]){"x", NULL}, tmp_env, PROOT, ROW10(S("%s/static", D)), ROW5_ENV);
	/* Nothing left in LD_PRELOAD but termux-exec: the variable goes. */
	expect(S("%s/static", D), (char *[]){"x", NULL}, ((char *[]){"PATH=/usr/bin", "LD_PRELOAD=/p/libtermux-exec.so", NULL}),
	       PROOT, ROW10(S("%s/static", D)), V("PATH=/usr/bin", TMP, HP, HE));

	/* 8: bionic in the data directory. */
	expect(bionic, (char *[]){"bionic", "a", NULL}, ENV, LINKER, V("bionic", bionic, "a"),
	       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", PRE,
	         S("TERMUX_EXEC__PROC_SELF_EXE=%s", bionic), HP, HE));
	/* The path handed to the linker stays unresolved. */
	expect(S("%s/alias", D), (char *[]){"alias", NULL}, ENV, LINKER, V("alias", S("%s/alias", D)),
	       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", PRE,
	         S("TERMUX_EXEC__PROC_SELF_EXE=%s/alias", D), HP, HE));
	/* Each spelling of linker64 as the interpreter. */
	for (const char *const *p = V("apexbionic", "bootbionic"); *p; p++) {
		const char *path = S("%s/%s", D, *p);
		expect(path, (char *[]){"x", NULL}, ENV, LINKER, V("x", path),
		       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", PRE,
		         S("TERMUX_EXEC__PROC_SELF_EXE=%s", path), HP, HE));
	}

	/* 9: musl, argv[0] kept byte for byte, without the shim. */
	expect(musl, (char *[]){"rg", "x", NULL}, ENV, LOADER, V(LOADER, "--argv0", "rg", musl, "x"),
	       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s", musl), HP, HE));
	/* An empty argv. */
	expect(musl, (char *[]){NULL}, ENV, LOADER, V(LOADER, "--argv0", musl, musl),
	       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s", musl), HP, HE));

	/* 2: linker64 by each of its names; argv[1] normalized. */
	if (chdir(R)) abort();
	expect(LINKER, (char *[]){"linker64", "rel/./prog", "a", NULL}, ENV, LINKER, V("linker64", "rel/./prog", "a"),
	       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", PRE,
	         S("TERMUX_EXEC__PROC_SELF_EXE=%s/rel/prog", R), HP, HE));
	{
		const char *apex = S("%s/apex/com.android.runtime/bin/linker64", SR);
		const char *boot = S("%s/system/bin/bootstrap/linker64", SR);
		expect(apex, (char *[]){"l", bionic, NULL}, ENV, apex, V("l", bionic),
		       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", PRE,
		         S("TERMUX_EXEC__PROC_SELF_EXE=%s", bionic), HP, HE));
		expect(boot, (char *[]){"l", NULL}, ENV, boot, V("l"),
		       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", PRE, HP, HE));
	}

	/* 3: the loader, without the shim. */
	expect(LOADER, (char *[]){"ld", "--preload", "/p.so", "--argv0", "a0", "prog", "x", NULL}, ENV, LOADER,
	       V("ld", "--preload", "/p.so", "--argv0", "a0", "prog", "x"),
	       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s/prog", R), HP, HE));
	expect(LOADER, (char *[]){"ld", NULL}, ENV, LOADER, V("ld"), V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", HP, HE));

	/* 4: the shim. */
	expect(SHIM, (char *[]){SHIM, LOADER, "x", NULL}, ENV, SHIM, V(SHIM, LOADER, "x"),
	       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", PRE, HP, HE, SE));

	/* 6: a native dynamic ELF under /system. */
	{
		const char *sh = S("%s/system/bin/sh", SR);
		expect(sh, (char *[]){"sh", "-c", "x", NULL}, ENV, sh, V("sh", "-c", "x"),
		       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", PRE, HP, HE));
	}

	/* 7: anything else under /system: static, 32-bit, another machine. */
	for (const char *const *p = V("static", "linker", "nonnative"); *p; p++) {
		const char *path = S("%s/system/bin/%s", SR, *p);
		expect(path, (char *[]){"x", NULL}, ENV, path, V("x"), V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", HP, HE));
	}

	/* 11: a static ELF of another machine and an ET_REL file: untouched. */
	for (const char *const *p = V("nonnative", "rel"); *p; p++) {
		const char *path = S("%s/%s", D, *p);
		struct hx_exec x;
		char *argv[] = {"x", "y", NULL};
		CHECK(!hx_route(path, argv, ENV, NULL, &x));
		CHECK(!strcmp(x.path, path) && x.envp == ENV && same(x.argv, V("x", "y")));
		hx_release(&x);
	}
}

static void test_shim(void) {
	char *musl = S("%s/musl", D), *agent = S("%s/claude", R);
	char *agent_env[] = {"PATH=/usr/bin", CAPTURE[0], CAPTURE[1], "SYSCALL_SHIM_ETC=/stale-etc", NULL};
	configure(1);

	/* 7: musl on x86_64 goes through the shim. */
	expect(musl, (char *[]){"rg", "x", NULL}, ENV, SHIM, V(SHIM, LOADER, "--argv0", "rg", musl, "x"),
	       V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", PRE, HP, HE, SE));

	/* agent.sh: exec $HARNESS_LOADER $HARNESS_AGENT --ide, from a shell that
	 * unset LD_PRELOAD. First the shim, with ours preloaded. */
	{
		struct hx_exec x, y;
		CHECK(!hx_route(LOADER, (char *[]){LOADER, (char *)agent, "--ide", "--continue", NULL}, agent_env, NULL, &x));
		CHECK(matches(__LINE__, &x, SHIM, V(SHIM, LOADER, agent, "--ide", "--continue"),
		              V("PATH=/usr/bin", S("LD_PRELOAD=%s", OURS), HP, HE, SE)));
		/* Then the shim's execv of the loader, routed in the shim. */
		t_self = SHIM;
		CHECK(!hx_route(x.argv[1], x.argv + 1, x.envp, NULL, &y));
		CHECK(matches(__LINE__, &y, LOADER, V(LOADER, agent, "--ide", "--continue"),
		              V("PATH=/usr/bin", SE, S("TERMUX_EXEC__PROC_SELF_EXE=%s", agent), HP, HE)));
		hx_release(&y);
		/* The same from a musl process the shim traces. */
		t_self = LOADER;
		t_traced = SHIM;
		CHECK(!hx_route(x.argv[1], x.argv + 1, x.envp, NULL, &y));
		CHECK(matches(__LINE__, &y, LOADER, V(LOADER, agent, "--ide", "--continue"),
		              V("PATH=/usr/bin", SE, S("TERMUX_EXEC__PROC_SELF_EXE=%s", agent), HP, HE)));
		hx_release(&y);
		/* Another tracer still gets the shim. */
		t_traced = "/data/data/x/files/usr/bin/strace";
		CHECK(!hx_route(x.argv[1], x.argv + 1, x.envp, NULL, &y));
		CHECK(!strcmp(y.path, SHIM));
		hx_release(&y);
		hx_release(&x);
	}

	/* agent.sh on arm64, where there is no shim. */
	configure(0);
	expect(LOADER, (char *[]){LOADER, (char *)agent, "--ide", NULL}, agent_env, LOADER, V(LOADER, agent, "--ide"),
	       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s", agent), HP, HE));
}

/* Every row, under the tracer the hooks describe as a proot. */
static void expect_under_proot(void) {
	const char *musl = S("%s/musl", D), *bionic = S("%s/bionic", D), *agent = S("%s/claude", R);
	const char *sh = S("%s/system/bin/sh", SR), *sys_static = S("%s/system/bin/static", SR);
	const char *const *stripped = V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", OTHER, HP, HE);
	const char *const *dropped = V("PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", HP, HE);
	const char *const *loader_env = V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s", agent), HP, HE);

	/* 1 and 5 as everywhere. */
	expect(LINKER, (char *[]){"linker64", BINPROOT, "x", NULL}, ENV, PROOT, V(BINPROOT, "x"), ROW5_ENV);
	expect(BINPROOT, (char *[]){"proot", "-r", "/x", NULL}, ENV, PROOT, V("proot", "-r", "/x"), ROW5_ENV);
	/* 3 without the shim, and 4 by its argv[1...]. */
	expect(LOADER, (char *[]){LOADER, (char *)agent, "--ide", NULL}, ENV, LOADER, V(LOADER, agent, "--ide"), loader_env);
	expect(SHIM, (char *[]){SHIM, LOADER, (char *)agent, NULL}, ENV, LOADER, V(LOADER, agent), loader_env);
	/* 9 without the shim where /lib/ld-musl-* is not bound, as asked where it is. */
	expect(musl, (char *[]){"rg", "x", NULL}, ENV, LOADER, V(LOADER, "--argv0", "rg", musl, "x"),
	       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s", musl), HP, HE));
	script(MUSLLIB, "");
	expect(musl, (char *[]){"rg", "x", NULL}, ENV, musl, V("rg", "x"), stripped);
	unlink(MUSLLIB);
	/* Everything else as asked; LD_PRELOAD kept only for a linker64 or musl
	 * interpreter, without termux-exec. */
	expect(LINKER, (char *[]){"linker64", (char *)bionic, "a", NULL}, ENV, LINKER, V("linker64", bionic, "a"), stripped);
	expect(sh, (char *[]){"sh", NULL}, ENV, sh, V("sh"), stripped);
	expect(sys_static, (char *[]){"x", NULL}, ENV, sys_static, V("x"), dropped);
	expect(bionic, (char *[]){"b", "a", NULL}, ENV, bionic, V("b", "a"), stripped);
	for (const char *const *p = V("static", "exec", "glibc", "foreign", "nonnative"); *p; p++) {
		const char *path = S("%s/%s", D, *p);
		expect(path, (char *[]){"x", NULL}, ENV, path, V("x"), dropped);
	}
}

/* Whether a musl program gets the shim, and a static one proot: nothing
 * changes under a tracer that is not a proot. */
static void expect_not_under_proot(int shim) {
	const char *musl = S("%s/musl", D), *st = S("%s/static", D);
	struct hx_exec x;
	CHECK(!hx_route(musl, (char *[]){"rg", NULL}, ENV, NULL, &x) && !strcmp(x.path, shim ? SHIM : LOADER));
	hx_release(&x);
	CHECK(!hx_route(st, (char *[]){"x", NULL}, ENV, NULL, &x) && !strcmp(x.path, PROOT));
	hx_release(&x);
}

static void test_under_proot(void) {
	pid_t pid;
	configure(1);

	t_traced = PROOT;
	expect_under_proot();

	/* linker64 running a proot named relative to its working directory. */
	pid = hold(S("%s/bin", PX), (char *[]){"/system/bin/linker64", "proot", "-r", "/x", NULL});
	t_traced = "/apex/com.android.runtime/bin/linker64";
	t_tracer_pid = pid;
	expect_under_proot();
	release(pid);
	/* And by an absolute path. */
	pid = hold(R, (char *[]){"/system/bin/linker64", PROOT, NULL});
	t_tracer_pid = pid;
	expect_under_proot();
	release(pid);

	/* linker64 running something else, the shim, and another tracer. */
	pid = hold(D, (char *[]){"/system/bin/linker64", "bionic", NULL});
	t_tracer_pid = pid;
	expect_not_under_proot(1);
	release(pid);
	t_tracer_pid = 0;
	t_traced = SHIM;
	expect_not_under_proot(0);
	t_traced = "/data/data/x/files/usr/bin/strace";
	expect_not_under_proot(1);
	configure(0);
}

/* Row 10 falls to row 11 without its captured values; $HARNESS_ETC's files
 * beyond the binding maximum are left out; musl finds nativeLibraryDir from its
 * own mapping. */
static void test_capture(void) {
	const char *st = S("%s/static", D);
	char *no_prefix[] = {CAPTURE[1], CAPTURE[2], NULL}, *no_etc[] = {CAPTURE[0], CAPTURE[2], NULL};
	struct hx_exec x;

	for (char **env = no_prefix;; env = no_etc) {
		hx_capture(env, L);
		CHECK(!hx_route(st, (char *[]){"x", NULL}, ENV, NULL, &x) && !strcmp(x.path, st) && x.envp == ENV);
		hx_release(&x);
		if (env == no_etc) break;
	}
	if (rename(PROOT, S("%s.away", PROOT))) abort();
	configure(0);
	CHECK(!hx_cfg.proot[0]);
	CHECK(!hx_route(st, (char *[]){"x", NULL}, ENV, NULL, &x) && !strcmp(x.path, st) && x.envp == ENV);
	hx_release(&x);
	/* A proot is then not recognized either: the prefix one is row 8. */
	CHECK(!hx_route(BINPROOT, (char *[]){"proot", NULL}, ENV, NULL, &x) && !strcmp(x.path, LINKER));
	hx_release(&x);
	if (rename(S("%s.away", PROOT), PROOT)) abort();

	/* More files than the maximum: the first by name kept, the rest left out,
	 * and said once however many processes capture. Only regular files, and
	 * links to them, are bound. */
	{
		char *many = S("%s/many", R), *env[] = {CAPTURE[0], S("HARNESS_ETC=%s", many), NULL};
		char *log = S("%s/capture.log", R), text[512] = "";
		int fd = open(log, O_WRONLY | O_CREAT | O_TRUNC, 0644), saved = dup(2);
		const char *names[HX_BINDS + 8];
		size_t etc_binds = 0, nn = 0;
		FILE *f;
		mkdirs(S("%s/d-sub", many));
		if (symlink("d-sub", S("%s/d-dirlink", many)) || mkfifo(S("%s/d-fifo", many), 0644)) abort();
		/* "e" before "e.conf", which a comparison of --bind=SRC:DST gets wrong. */
		names[nn++] = "e";
		names[nn++] = "e.conf";
		for (int i = 0; i < HX_BINDS + 2; i++)
			names[nn++] = S("f%02d", i);
		/* Written last first, so directory order is not name order. */
		for (size_t i = nn; i-- > 0;)
			script(S("%s/%s", many, names[i]), "");
		dup2(fd, 2);
		for (int i = 0; i < 3; i++)
			hx_capture(env, L);
		dup2(saved, 2);
		close(fd);
		close(saved);
		for (size_t i = 0; i < hx_cfg.nbind; i++)
			if (!strncmp(hx_cfg.bind[i], S("--bind=%s/", many), strlen(many) + 8)) {
				CHECK(i == etc_binds &&
				      !strcmp(hx_cfg.bind[i], S("--bind=%s/%s:/etc/%s", many, names[i], names[i])));
				etc_binds++;
			}
		CHECK(etc_binds == HX_BINDS - 3);
		/* With the certificates, the musl loader and glibc's ld.so. */
		CHECK(hx_cfg.nbind == HX_BINDS && hx_cfg.glibc_bind);
		f = fopen(log, "r");
		CHECK(f && fread(text, 1, sizeof text - 1, f) > 0);
		if (f) fclose(f);
		CHECK(strstr(text, "HARNESS_ETC") && strchr(text, '\n') == text + strlen(text) - 1);
	}

	/* The capture in musl passes no libdir. /proc/self/exe is not asked:
	 * it names the program under proot. */
	{
		char exe[PATH_MAX], *slash;
		ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
		CHECK(n > 0);
		exe[n > 0 ? n : 0] = 0;
		slash = strrchr(exe, '/');
		if (slash) *slash = 0;
		t_self = "/nowhere/else";
		hx_capture(CAPTURE, NULL);
		CHECK(!strcmp(hx_cfg.libdir, exe));
	}
	configure(0);
}

/* Routes path with ever more arguments until the arrays no longer fit inline:
 * every one must come out whole, with off arguments in front of P, the
 * environment xenv, and nothing written past struct hx_exec. Returns the most
 * arguments that fit inline. */
static size_t boundary(int line, const char *path, char **argv, char **env, const char *xpath, size_t off,
                       const char *const *xenv) {
	struct {
		struct hx_exec x;
		unsigned char canary[256];
	} b;
	size_t n, inline_max = 0;
	argv[0] = "x";
	for (n = 1; n < 4000; n++) {
		int ok, intact = 1;
		argv[n] = S("a%zu", n);
		argv[n + 1] = NULL;
		memset(b.canary, 0x5a, sizeof b.canary);
		if (hx_route(path, argv, env, NULL, &b.x)) break;
		ok = !strcmp(b.x.path, xpath) && !strcmp(b.x.argv[off + n], argv[n]) && !b.x.argv[off + n + 1] &&
		     same(b.x.envp, xenv);
		for (size_t i = 0; i < sizeof b.canary; i++)
			intact &= b.canary[i] == 0x5a;
		if (!ok || !intact) {
			fprintf(stderr, "line %d: %s with %zu arguments: %s\n", line, path, n + 1, ok ? "overflow" : "wrong");
			failures++;
			break;
		}
		if (b.x.map) {
			hx_release(&b.x);
			break;
		}
		inline_max = n;
	}
	checks++;
	if (!(inline_max > 50 && n == inline_max + 1)) failures++;
	return inline_max + 1;
}

/* Row 10's arguments and environment at the largest list that fits inline, and
 * the first that does not; the same for a long LD_PRELOAD as asked under proot
 * and through row 5; then E2BIG when that cannot be mapped. */
static void test_row10_e2big(void) {
	const char *st = S("%s/static", D), *bionic = S("%s/bionic", D);
	char **argv = calloc(4002, sizeof *argv), *pre = malloc(1100), *lenv[4];
	struct hx_exec x;
	size_t n;
	configure(0);
	n = boundary(__LINE__, st, argv, ENV, PROOT, 7, ROW5_ENV);
	printf("row 10: %zu arguments inline, %zu mapped\n", n, n + 1);

	/* About a kilobyte of preloads that are not termux-exec, and one that is. */
	strcpy(pre, "LD_PRELOAD=/p/libtermux-exec.so");
	while (strlen(pre) < 1050)
		strcat(pre, ":/a/long/preload/path/libx.so");
	lenv[0] = "PATH=/usr/bin";
	lenv[1] = pre;
	lenv[2] = NULL;
	{
		const char *stripped = S("LD_PRELOAD=%s", pre + strlen("LD_PRELOAD=/p/libtermux-exec.so:"));
		boundary(__LINE__, BINPROOT, argv, lenv, PROOT, 0, V("PATH=/usr/bin", TMP, stripped, HP, HE));
		t_traced = PROOT;
		boundary(__LINE__, bionic, argv, lenv, bionic, 0, V("PATH=/usr/bin", stripped, HP, HE));
		configure(0);
	}

	pid_t pid = fork();
	if (!pid) {
		FILE *f = fopen("/proc/self/status", "r");
		char line[256];
		unsigned long kb = 0;
		while (fgets(line, sizeof line, f))
			if (sscanf(line, "VmSize: %lu", &kb) == 1) break;
		struct rlimit rl = {kb * 1024 + 4096, kb * 1024 + 4096};
		if (setrlimit(RLIMIT_AS, &rl)) _exit(2);
		/* One more than row 10 fits inline. */
		for (size_t i = 1; i <= n; i++)
			argv[i] = "a";
		argv[n + 1] = NULL;
		_exit(hx_route(st, argv, ENV, NULL, &x) == E2BIG ? 0 : 1);
	}
	int status;
	waitpid(pid, &status, 0);
	CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

static void test_rewrite_and_scripts(void) {
	const char *hxtool = S("%s/bin/hxtool", PX), *bionic = S("%s/bionic", D), *musl = S("%s/musl", D);
	const char *base[] = {"PATH=/usr/bin", "LD_LIBRARY_PATH=/l", "SYSCALL_SHIM_ETC=/stale-etc", PRE};
	configure(0);
#define B6(pse) V(base[0], base[1], base[2], base[3], S("TERMUX_EXEC__PROC_SELF_EXE=%s", pse), HP, HE)

	expect("/usr/bin/hxtool", (char *[]){"hxtool", NULL}, ENV, LINKER, V("hxtool", hxtool), B6(hxtool));
	expect("/bin/sh", (char *[]){"sh", NULL}, ENV, LINKER, V("sh", S("%s/bin/sh", PX)), B6(S("%s/bin/sh", PX)));
	expect_err("/usr/bin/hx-absent", (char *[]){"x", NULL}, ENOENT);

	/* The interpreter is rewritten, and named by TERMUX_EXEC__PROC_SELF_EXE. */
	expect(S("%s/tool.sh", D), (char *[]){"tool.sh", "a", NULL}, ENV, LINKER,
	       V("/usr/bin/hxtool", hxtool, "-x", S("%s/tool.sh", D), "a"), B6(hxtool));
	expect(S("%s/spaces.sh", D), (char *[]){"s", NULL}, ENV, LINKER,
	       V(bionic, bionic, "arg with  spaces", S("%s/spaces.sh", D)), B6(bionic));
	expect(S("%s/noarg.sh", D), (char *[]){"s", "z", NULL}, ENV, LINKER, V(bionic, bionic, S("%s/noarg.sh", D), "z"),
	       B6(bionic));
	expect(S("%s/tabs.sh", D), (char *[]){"tabs.sh", "z", NULL}, ENV, LOADER,
	       V(LOADER, "--argv0", musl, musl, "-a\tb", S("%s/tabs.sh", D), "z"),
	       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s", musl), HP, HE));

	/* Four levels are followed, a fifth is ELOOP. */
	expect(S("%s/s1", D), (char *[]){"s1", "q", NULL}, ENV, LINKER,
	       V(bionic, bionic, S("%s/s4", D), S("%s/s3", D), S("%s/s2", D), "a1", S("%s/s1", D), "q"), B6(bionic));
	expect_err(S("%s/s0", D), (char *[]){"s0", NULL}, ELOOP);

	expect_err(S("%s/text", D), (char *[]){"x", NULL}, ENOEXEC);
	expect_err(S("%s/blank.sh", D), (char *[]){"x", NULL}, ENOEXEC);
	expect_err(S("%s/empty", D), (char *[]){"x", NULL}, ENOEXEC);
	expect_err(S("%s/noexec", D), (char *[]){"x", NULL}, EACCES);
	expect_err(S("%s/absent", D), (char *[]){"x", NULL}, ENOENT);
	expect_err(S("%s/dir", D), (char *[]){"x", NULL}, EACCES);
	expect_err(S("%s/bionic/x", D), (char *[]){"x", NULL}, ENOTDIR);

	/* A relative path against a spawn's directory. */
	{
		struct hx_exec x;
		CHECK(!hx_route("bionic", (char *[]){"b", NULL}, ENV, D, &x));
		CHECK(matches(__LINE__, &x, LINKER, V("b", bionic), B6(bionic)));
		hx_release(&x);
	}

	/* PATH search. */
	{
		struct hx_exec x;
		CHECK(!hx_route_search("bionic", S("%s/none:%s", R, D), (char *[]){"b", NULL}, ENV, NULL, &x));
		CHECK(!strcmp(x.path, LINKER) && !strcmp(x.argv[1], bionic));
		hx_release(&x);
		CHECK(!hx_route_search("bionic", S("%s/none:%s", R, D), (char *[]){"b", NULL}, ENV, NULL, &x));
		hx_release(&x);
		CHECK(hx_route_search("noexec", D, (char *[]){"b", NULL}, ENV, NULL, &x) == EACCES);
		CHECK(hx_route_search("absent", S("%s:%s", D, R), (char *[]){"b", NULL}, ENV, NULL, &x) == ENOENT);
		CHECK(hx_route_search("text", D, (char *[]){"b", NULL}, ENV, NULL, &x) == ENOEXEC);
		CHECK(hx_route_search("", D, (char *[]){"b", NULL}, ENV, NULL, &x) == ENOENT);
		CHECK(!hx_route_search(bionic, "/nowhere", (char *[]){"b", NULL}, ENV, NULL, &x));
		hx_release(&x);
		if (chdir(D)) abort();
		CHECK(!hx_route_search("bionic", S("/nowhere::%s", R), (char *[]){"b", NULL}, ENV, NULL, &x));
		CHECK(!strcmp(x.argv[1], bionic));
		hx_release(&x);
		if (chdir(R)) abort();
	}
#undef B6
}

static void test_proc_self_exe(void) {
	const char *musl = S("%s/musl", D);
	const char *linker = "/apex/com.android.runtime/bin/linker64";
	char buf[PATH_MAX];
	configure(0);

	/* exec of /proc/self/exe from a process a loader started. */
	t_self = LOADER;
	expect("/proc/self/exe", (char *[]){"me", "y", NULL}, ENV, LOADER, V(LOADER, "--argv0", "me", musl, "y"),
	       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s", musl), HP, HE));
	expect(S("/proc/%d/exe", getpid()), (char *[]){"me", NULL}, ENV, LOADER, V(LOADER, "--argv0", "me", musl),
	       V("PATH=/usr/bin", "SYSCALL_SHIM_ETC=/stale-etc", S("TERMUX_EXEC__PROC_SELF_EXE=%s", musl), HP, HE));
	t_self = linker;
	CHECK(!strcmp(hx_proc_exe("/proc/self/exe", buf) ?: "", musl));
	CHECK(!strcmp(hx_proc_exe(S("/proc/%d/exe", getpid()), buf) ?: "", musl));
	CHECK(!hx_proc_exe("/proc/self/cwd", buf));
	CHECK(!hx_proc_exe(S("/proc/%d/cwd", getpid()), buf));
	CHECK(!hx_proc_exe("/proc/selfish/exe", buf));
	CHECK(!hx_proc_exe("/tmp/exe", buf));
	CHECK(!hx_proc_exe(NULL, buf));

	/* Not started through a loader: the real answer stands. This program is
	 * a glibc one, so without libproot.so it runs as asked. */
	t_self = NULL;
	CHECK(!hx_proc_exe("/proc/self/exe", buf));
	{
		struct hx_exec x;
		hx_cfg.proot[0] = 0;
		CHECK(!hx_route("/proc/self/exe", (char *[]){"me", NULL}, ENV, NULL, &x));
		CHECK(!strcmp(x.path, "/proc/self/exe") && x.envp == ENV);
		hx_release(&x);
	}
	t_self = linker;
	hx_cfg.self[0] = 0;
	CHECK(!hx_proc_exe("/proc/self/exe", buf));
	configure(0);

	/* Another process's, from its environment, found past a long entry. */
	{
		int p[2];
		char c;
		char *long_entry = malloc(3000);
		memset(long_entry, 'x', 2999);
		memcpy(long_entry, "LONG=", 5);
		long_entry[2999] = 0;
		if (pipe2(p, O_CLOEXEC)) abort();
		pid_t pid = fork();
		if (!pid) {
			char *env[] = {long_entry, S("TERMUX_EXEC__PROC_SELF_EXE=%s", musl), "Z=1", NULL};
			execve("/bin/sleep", (char *[]){"sleep", "10", NULL}, env);
			_exit(127);
		}
		close(p[1]);
		CHECK(read(p[0], &c, 1) == 0);
		close(p[0]);
		t_other = linker;
		CHECK(!strcmp(hx_proc_exe(S("/proc/%d/exe", pid), buf) ?: "", musl));
		t_other = "/usr/bin/sleep";
		CHECK(!hx_proc_exe(S("/proc/%d/exe", pid), buf));
		t_other = NULL;
		kill(pid, SIGKILL);
		waitpid(pid, NULL, 0);
	}
}

static void test_large_and_e2big(void) {
	const char *musl = S("%s/musl", D);
	size_t n = 5000;
	char **argv = calloc(n + 1, sizeof *argv);
	struct hx_exec x;
	configure(0);
	for (size_t i = 0; i < n; i++)
		argv[i] = S("arg%zu", i);
	CHECK(!hx_route(musl, argv, ENV, NULL, &x));
	CHECK(!strcmp(x.argv[0], LOADER) && !strcmp(x.argv[3], musl) && !strcmp(x.argv[4], "arg1") &&
	      !strcmp(x.argv[n + 2], "arg4999") && !x.argv[n + 3]);
	/* Too large for struct hx_exec, so mapped. */
	CHECK(x.map != NULL);
	hx_release(&x);

	/* With no address space left for the mapping, E2BIG. */
	pid_t pid = fork();
	if (!pid) {
		FILE *f = fopen("/proc/self/status", "r");
		char line[256];
		unsigned long kb = 0;
		while (fgets(line, sizeof line, f))
			if (sscanf(line, "VmSize: %lu", &kb) == 1) break;
		struct rlimit rl = {kb * 1024 + 4096, kb * 1024 + 4096};
		if (setrlimit(RLIMIT_AS, &rl)) _exit(2);
		_exit(hx_route(musl, argv, ENV, NULL, &x) == E2BIG ? 0 : 1);
	}
	int status;
	waitpid(pid, &status, 0);
	CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

/* In a vfork child, on the stack musl's posix_spawn gives a child that routes
 * its own call, with the allocator forbidden. The stack is painted first, so
 * the depth the router reached can be read off afterwards. */
static struct {
	const char *musl, *script, *hxtool, *search, *linkers, *st, *bionic, *bin;
	char **big;
	pid_t tracer;
	int result;
} vf;

static int vfork_child(void *arg) {
	struct hx_exec x;
	(void)arg;
	forbid_alloc = 1;
	vf.result = 1;
	if (hx_route(vf.musl, (char *[]){"rg", "x", NULL}, ENV, NULL, &x)) _exit(0);
	if (strcmp(x.path, LOADER) || strcmp(x.argv[2], "rg") || strcmp(x.argv[3], vf.musl) || x.map) _exit(0);
	hx_release(&x);
	vf.result = 2;
	if (hx_route(vf.script, (char *[]){"tool.sh", NULL}, ENV, NULL, &x)) _exit(0);
	if (strcmp(x.path, LINKER) || strcmp(x.argv[1], vf.hxtool)) _exit(0);
	hx_release(&x);
	vf.result = 3;
	if (hx_route(vf.big[0], vf.big, ENV, NULL, &x)) _exit(0);
	hx_release(&x);
	vf.result = 4;
	if (hx_route("/proc/self/exe", (char *[]){"me", NULL}, ENV, NULL, &x)) _exit(0);
	hx_release(&x);
	vf.result = 5;
	if (hx_route_search("musl", vf.search, (char *[]){"rg", NULL}, ENV, NULL, &x) || x.map) _exit(0);
	hx_release(&x);
	vf.result = 6;
	if (hx_route(vf.st, (char *[]){"x", NULL}, ENV, NULL, &x) || strcmp(x.path, PROOT) || x.map) _exit(0);
	hx_release(&x);
	vf.result = 7;
	if (hx_route(LINKER, (char *[]){"l", "proot", NULL}, ENV, vf.bin, &x) || strcmp(x.path, PROOT)) _exit(0);
	hx_release(&x);
	/* The deepest: a PATH search, row 1's argv[1], then the tracer's. */
	vf.result = 8;
	t_traced = "/apex/com.android.runtime/bin/linker64";
	t_tracer_pid = vf.tracer;
	if (hx_route_search("linker64", vf.linkers, (char *[]){"linker64", (char *)vf.bionic, NULL}, ENV, NULL, &x) ||
	    strcmp(x.path, LINKER) || x.envp == ENV)
		_exit(0);
	hx_release(&x);
	vf.result = 0;
	_exit(0);
}

static void test_vfork_stack(void) {
	size_t page = (size_t)sysconf(_SC_PAGESIZE), size = 1024 + 2 * PATH_MAX + sizeof(struct hx_exec);
	size_t span = (size + page - 1) / page * page, used;
	char *region = mmap(NULL, page + span, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0), *low;
	int status;
	pid_t pid;
	configure(1);
	vf.musl = S("%s/musl", D);
	vf.script = S("%s/tool.sh", D);
	vf.hxtool = S("%s/bin/hxtool", PX);
	vf.search = S("/nowhere:%s", D);
	vf.linkers = S("/nowhere:%s/system/bin", SR);
	vf.st = S("%s/static", D);
	vf.bionic = S("%s/bionic", D);
	vf.bin = S("%s/bin", PX);
	vf.big = calloc(3001, sizeof *vf.big);
	for (int i = 0; i < 3000; i++)
		vf.big[i] = i ? "a" : (char *)vf.musl;
	vf.tracer = hold(vf.bin, (char *[]){"/system/bin/linker64", "proot", NULL});
	t_self = SHIM;
	vf.result = -1;
	/* A guard page, then the stack above it, painted. */
	mprotect(region, page, PROT_NONE);
	memset(region + page, 0xa5, span);
	pid = clone(vfork_child, region + page + size, CLONE_VM | CLONE_VFORK | SIGCHLD, NULL);
	forbid_alloc = 0;
	CHECK(pid > 0);
	CHECK(waitpid(pid, &status, 0) == pid);
	CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
	if (vf.result) fprintf(stderr, "vfork child stopped at step %d (status %#x)\n", vf.result, status);
	CHECK(vf.result == 0);
	for (low = region + page; low < region + page + size && (unsigned char)*low == 0xa5; low++)
		;
	used = (size_t)(region + page + size - low);
	printf("struct hx_exec: %zu bytes; deepest stack use in the vfork child: %zu of %zu bytes\n",
	       sizeof(struct hx_exec), used, size);
	CHECK(used + 2048 <= size);
	CHECK(sizeof(struct hx_exec) < 40960);
	munmap(region, page + span);
	release(vf.tracer);
	configure(0);
}

static unsigned long status_kb(const char *key) {
	FILE *f = fopen("/proc/self/status", "r");
	char line[256];
	unsigned long kb = 0;
	size_t n = strlen(key);
	while (fgets(line, sizeof line, f))
		if (!strncmp(line, key, n) && line[n] == ':') kb = strtoul(line + n + 1, NULL, 10);
	fclose(f);
	return kb;
}

/* vfork and exec, as a shell does, a thousand times: the parent must not grow. */
static void test_vfork_exec_leak(void) {
	char *copy = S("%s/system/bin/true", SR);
	char *argv[] = {"true", "a", "b", NULL};
	/* A long environment, as a shell with many exports hands on. */
	char **big = calloc(301, sizeof *big);
	int null = open("/dev/null", O_WRONLY);
	for (int i = 0; ENV[i]; i++)
		big[i] = ENV[i];
	for (int i = 6; i < 300; i++)
		big[i] = S("HX_VARIABLE_%03d=/data/data/com.termux/files/usr/share/%d", i, i);
	configure(1);
	/* The host's /bin/true is a glibc program: run as asked, not through the
	 * fixture that stands in for libproot.so. */
	hx_cfg.proot[0] = 0;
	t_self = "/system/bin/sh";
	if (system(S("cp /bin/true '%s'", copy))) abort();
	for (char **envp = ENV;; envp = big) {
		for (const char *const *p = V("/bin/true", copy); *p; p++) {
			unsigned long rss = 0, vm = 0;
			int bad = 0, envc = 0;
			while (envp[envc])
				envc++;
			for (int i = 0; i < 1050; i++) {
				int status;
				pid_t pid;
				/* The first runs settle the allocator and the libraries. */
				if (i == 50) {
					rss = status_kb("RssAnon");
					vm = status_kb("VmSize");
				}
				pid = vfork();
				if (!pid) {
					dup2(null, 2);
					_exit(100 - hx_execve(*p, argv, envp));
				}
				if (waitpid(pid, &status, 0) != pid || !WIFEXITED(status) || WEXITSTATUS(status)) bad++;
			}
			printf("vfork+exec %s with %d environment entries x1000: RssAnon %lu -> %lu kB, VmSize %lu -> %lu kB\n",
			       *p, envc, rss, status_kb("RssAnon"), vm, status_kb("VmSize"));
			CHECK(!bad);
			CHECK(status_kb("RssAnon") <= rss + 256);
			CHECK(status_kb("VmSize") <= vm + 256);
		}
		if (envp == big) break;
	}
	close(null);
}

int main(void) {
	if (getenv("HX_HOLD"))
		for (;;)
			pause();
	setup();
	configure(0);
	PRE = S("LD_PRELOAD=%s:/o/other.so", OURS);
	HP = S("HARNESS_PREFIX=%s", PX);
	HE = S("HARNESS_ETC=%s", ETC);
	SE = S("SYSCALL_SHIM_ETC=%s", ETC);
	OTHER = "LD_PRELOAD=/o/other.so";
	TMP = S("PROOT_TMP_DIR=%s/tmp", PX);
	EXECBIN = S("%s/bin", PX);
	B_HOSTS = S("--bind=%s/hosts:/etc/hosts", ETC);
	B_RESOLV = S("--bind=%s/resolv.conf:/etc/resolv.conf", ETC);
	B_CERT = S("--bind=%s/etc/tls/cert.pem:/etc/ssl/certs/ca-certificates.crt", PX);
	B_MUSL = S("--bind=%s:%s", LOADER, HX_MUSL_LIB);
	B_GLIBC = S("--bind=%s:%s", GLIBC, HX_GLIBC_LIB);

	test_units();
	test_rows();
	test_shim();
	test_under_proot();
	test_capture();
	test_row10_e2big();
	test_rewrite_and_scripts();
	test_proc_self_exe();
	test_large_and_e2big();
	test_vfork_stack();
	test_vfork_exec_leak();

	if (system(S("rm -rf '%s'", R))) fprintf(stderr, "could not remove %s\n", R);
	printf("%d checks, %d failures\n", checks, failures);
	return failures != 0;
}
