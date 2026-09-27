/*
 * A program for the proot host tests of P4. It traps the calls Android's app
 * policy traps, as that policy does, with a seccomp filter whose action is
 * SECCOMP_RET_TRAP, then makes them by raw syscall(2) and prints what each
 * returned. Run directly, the first trapped call kills it with SIGSYS.
 *
 *   legacy setid    the set-id calls
 *   legacy blocked  a call bionic's blocklist names, and an unmarked one
 *   legacy own      a SIGSYS from a filter of its own, for a call the policy
 *                   allows
 *   legacy fork     fork's registers, on x86_64
 *   legacy x86_64   the x86_64 translations, fork's registers and the red zone
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__x86_64__)
#define ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define ARCH AUDIT_ARCH_AARCH64
#endif

static const int trapped[] = {
	SYS_setuid, SYS_setgid, SYS_setreuid, SYS_setregid, SYS_setfsuid, SYS_setfsgid,
	SYS_chroot, SYS_sethostname,
#if defined(__x86_64__)
	SYS_fork, SYS_mknod, SYS_creat, SYS_pause, SYS_alarm, SYS_epoll_create,
	SYS_inotify_init, SYS_signalfd, SYS_eventfd, SYS_dup2, SYS_poll, SYS_uselib,
#endif
#ifdef SYS_faccessat2
	SYS_faccessat2,
#else
	439,
#endif
};

static void trap(const int *calls, int N) {
	struct sock_filter filter[5 + 2 * N];
	struct sock_fprog prog = { .len = 0, .filter = filter };
	int i;

#define ADD(...) (filter[prog.len++] = (struct sock_filter) __VA_ARGS__)
	ADD(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)));
	ADD(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ARCH, 1, 0));
	ADD(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
	ADD(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)));
	for (i = 0; i < N; i++) {
		ADD(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, calls[i], 0, 1));
		ADD(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP));
	}
	ADD(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
#undef ADD
	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) {
		perror("seccomp");
		exit(2);
	}
}

/* The result of a raw call: the value, or the name of its errno.  */
static const char *r(long ret) {
	static char buf[8][32];
	static int next;
	char *b = buf[next++ % 8];

	if (ret >= 0)
		snprintf(b, 32, "%ld", ret);
	else
		snprintf(b, 32, "%s", errno == EPERM ? "EPERM" : errno == ENOSYS ? "ENOSYS"
			: errno == EBADF ? "EBADF" : errno == EINVAL ? "EINVAL"
			: errno == EINTR ? "EINTR" : strerror(errno));
	return b;
}

static void setid(void) {
	uid_t u = getuid();
	gid_t g = getgid();

	printf("setuid same=%s other=%s\n", r(syscall(SYS_setuid, u)), r(syscall(SYS_setuid, u + 1)));
	printf("setgid same=%s other=%s\n", r(syscall(SYS_setgid, g)), r(syscall(SYS_setgid, g + 1)));
	printf("setreuid same=%s unchanged=%s other=%s\n", r(syscall(SYS_setreuid, u, u)),
		r(syscall(SYS_setreuid, -1, -1)), r(syscall(SYS_setreuid, u, u + 1)));
	printf("setregid same=%s unchanged=%s other=%s\n", r(syscall(SYS_setregid, g, g)),
		r(syscall(SYS_setregid, -1, -1)), r(syscall(SYS_setregid, g + 1, -1)));
	printf("setfsuid current=%d\n", syscall(SYS_setfsuid, u + 7) == (long) geteuid());
	printf("setfsgid current=%d\n", syscall(SYS_setfsgid, g + 7) == (long) getegid());
}

static volatile int own_sigsys;

static void on_sigsys(int signum, siginfo_t *info, void *context) {
	(void) signum;
	(void) context;
	own_sigsys = info->si_syscall;
}

static void own(void) {
	static const int getpid_only[] = { SYS_getpid };
	struct sigaction action = { .sa_sigaction = on_sigsys, .sa_flags = SA_SIGINFO };

	sigaction(SIGSYS, &action, NULL);
	trap(getpid_only, 1);
	syscall(SYS_getpid);
	printf("own sigsys=%d\n", own_sigsys == SYS_getpid);
}

static void blocked(void) {
	printf("chroot=%s sethostname=%s faccessat2=%s\n", r(syscall(SYS_chroot, "/")),
		r(syscall(SYS_sethostname, "x", 1)),
		r(syscall(trapped[sizeof trapped / sizeof *trapped - 1], AT_FDCWD, "/", F_OK, 0)));
}

#if defined(__x86_64__)
/* SYS_fork with known values in the six argument registers; in both processes
 * each must be unchanged after it.  The child exits with 0 when they are, the
 * parent returns the child's pid, negated when they are not.  */
long fork_regs(void);
__asm__(
	".text\n"
	"fork_regs:\n"
	"	movabs $0x1111111111111111, %rdi\n"
	"	movabs $0x2222222222222222, %rsi\n"
	"	movabs $0x3333333333333333, %rdx\n"
	"	movabs $0x4444444444444444, %r10\n"
	"	movabs $0x5555555555555555, %r8\n"
	"	movabs $0x6666666666666666, %r9\n"
	"	mov $57, %eax\n"
	"	syscall\n"
	"	xor %ecx, %ecx\n"
	"	movabs $0x1111111111111111, %r11\n"
	"	cmp %r11, %rdi\n"
	"	setne %cl\n"
	"	movabs $0x2222222222222222, %r11\n"
	"	cmp %r11, %rsi\n"
	"	jne 1f\n"
	"	movabs $0x3333333333333333, %r11\n"
	"	cmp %r11, %rdx\n"
	"	jne 1f\n"
	"	movabs $0x4444444444444444, %r11\n"
	"	cmp %r11, %r10\n"
	"	jne 1f\n"
	"	movabs $0x5555555555555555, %r11\n"
	"	cmp %r11, %r8\n"
	"	jne 1f\n"
	"	movabs $0x6666666666666666, %r11\n"
	"	cmp %r11, %r9\n"
	"	je 2f\n"
	"1:	mov $1, %ecx\n"
	"2:	test %rax, %rax\n"
	"	jnz 3f\n"
	"	mov %ecx, %edi\n"
	"	mov $231, %eax\n"
	"	syscall\n"
	"3:	test %ecx, %ecx\n"
	"	jz 4f\n"
	"	neg %rax\n"
	"4:	ret\n");

/* A leaf function: it fills the 128 bytes below the stack pointer, makes the
 * call @nr(@a, @b, @c) and returns its result, or 0x7eadbeef when those bytes
 * changed.  It moves the stack pointer first, so that it is not the one of
 * any call proot traced before.  */
long red_zone(long nr, long a, long b, long c);
__asm__(
	".text\n"
	"red_zone:\n"
	"	sub $4096, %rsp\n"
	"	call 5f\n"
	"	add $4096, %rsp\n"
	"	ret\n"
	"5:	movabs $0x5a5a5a5a5a5a5a5a, %r8\n"
	"	mov $-128, %r9\n"
	"1:	mov %r8, (%rsp,%r9)\n"
	"	add $8, %r9\n"
	"	jnz 1b\n"
	"	mov %rdi, %rax\n"
	"	mov %rsi, %rdi\n"
	"	mov %rdx, %rsi\n"
	"	mov %rcx, %rdx\n"
	"	syscall\n"
	"	movabs $0x5a5a5a5a5a5a5a5a, %r8\n"
	"	mov $-128, %r9\n"
	"2:	cmp %r8, (%rsp,%r9)\n"
	"	jne 3f\n"
	"	add $8, %r9\n"
	"	jnz 2b\n"
	"	ret\n"
	"3:	mov $0x7eadbeef, %eax\n"
	"	ret\n");

static void on_alarm(int signum) {
	(void) signum;
}

static const char *fd_kind(long fd) {
	static char link[8][64];
	static int next;
	char *b = link[next++ % 8], path[64];
	ssize_t n;

	if (fd < 0)
		return r(fd);
	snprintf(path, sizeof path, "/proc/self/fd/%ld", fd);
	n = readlink(path, b, 63);
	b[n < 0 ? 0 : n] = 0;
	return b;
}

static void fork_test(void) {
	long pid;
	int status;

	pid = fork_regs();
	waitpid(pid < 0 ? -pid : pid, &status, 0);
	printf("fork parent=%s child=%s\n", pid > 0 ? "ok" : "changed",
		WIFEXITED(status) && WEXITSTATUS(status) == 0 ? "ok" : "changed");
}

static void x86_64(const char *dir) {
	char path[4096];
	struct stat st;
	struct itimerval it = { .it_value = { 0, 200000 } };
	sigset_t mask;
	long fd, a, b, c;
	unsigned long long value;

	fork_test();

	snprintf(path, sizeof path, "%s/fifo", dir);
	a = syscall(SYS_mknod, path, S_IFIFO | 0600, 0);
	printf("mknod=%s fifo=%d\n", r(a), stat(path, &st) == 0 && S_ISFIFO(st.st_mode));

	snprintf(path, sizeof path, "%s/creat", dir);
	fd = syscall(SYS_creat, path, 0600);
	a = write(fd, "xy", 2);
	close(fd);
	fd = syscall(SYS_creat, path, 0600);
	printf("creat=%s truncated=%d wronly=%d\n", fd < 0 ? r(fd) : "fd",
		stat(path, &st) == 0 && st.st_size == 0, (fcntl(fd, F_GETFL) & O_ACCMODE) == O_WRONLY);
	close(fd);

	signal(SIGALRM, on_alarm);
	setitimer(ITIMER_REAL, &it, NULL);
	printf("pause=%s\n", r(syscall(SYS_pause)));

	a = syscall(SYS_alarm, 5);
	b = syscall(SYS_alarm, 3);
	c = syscall(SYS_alarm, 0);
	setitimer(ITIMER_REAL, &it, NULL);
	printf("alarm %ld %ld %ld under-a-second=%ld\n", a, b, c, syscall(SYS_alarm, 0));

	printf("epoll_create zero=%s one=%s\n", r(syscall(SYS_epoll_create, 0)),
		fd_kind(syscall(SYS_epoll_create, 1)));
	printf("inotify_init=%s\n", fd_kind(syscall(SYS_inotify_init)));
	sigemptyset(&mask);
	printf("signalfd=%s\n", fd_kind(syscall(SYS_signalfd, -1, &mask, 8)));
	fd = syscall(SYS_eventfd, 3);
	value = 0;
	a = read(fd, &value, sizeof value);
	printf("eventfd=%s value=%llu\n", fd_kind(fd), value);

	fd = open("/dev/null", O_RDONLY);
	close(40);
	printf("dup2 same=%d closed=%s other=%s\n", syscall(SYS_dup2, fd, fd) == fd,
		r(syscall(SYS_dup2, 40, 40)), r(syscall(SYS_dup2, fd, 41)));

	printf("uselib=%s\n", r(syscall(SYS_uselib, "/nonexistent")));

	a = red_zone(SYS_alarm, 0, 0, 0);
	b = red_zone(SYS_poll, 0, 0, 20);
	printf("red zone alarm=%s poll=%s\n", a == 0x7eadbeef ? "changed" : "kept",
		b == 0x7eadbeef ? "changed" : "kept");
}
#endif

int main(int argc, char **argv) {
	if (argc < 2)
		return 2;
	trap(trapped, sizeof trapped / sizeof *trapped);
	if (!strcmp(argv[1], "setid"))
		setid();
	else if (!strcmp(argv[1], "blocked"))
		blocked();
	else if (!strcmp(argv[1], "own"))
		own();
#if defined(__x86_64__)
	else if (!strcmp(argv[1], "fork"))
		fork_test();
	else if (!strcmp(argv[1], "x86_64") && argc == 3)
		x86_64(argv[2]);
#endif
	else
		return 2;
	return 0;
}
