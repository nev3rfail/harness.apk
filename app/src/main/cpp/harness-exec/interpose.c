/*
 * The bionic side of exec routing: libtermuxexec.so, preloaded into every bionic
 * program the app starts, interposes the exec family, posix_spawn, readlink and
 * realpath. Derived from termux-exec (termux-play-store/termux-exec, 47633d5,
 * Apache-2.0); see NOTICE.
 *
 * The exec variants follow bionic's own
 * (https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/bionic/exec.cpp),
 * so each ends in the execve below.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <paths.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "harness-exec.h"

#define EXPORT __attribute__((visibility("default")))

/* nativeLibraryDir is the directory this library was loaded from. */
__attribute__((constructor)) static void capture(int argc, char **argv, char **envp) {
	Dl_info info;
	char dir[PATH_MAX];
	(void)argc;
	(void)argv;
	dir[0] = 0;
	if (dladdr((void *)capture, &info) && info.dli_fname && strlen(info.dli_fname) < sizeof dir) {
		char *slash;
		strcpy(dir, info.dli_fname);
		slash = strrchr(dir, '/');
		if (slash) *slash = 0;
	}
	hx_capture(envp, dir);
}

EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
	errno = -hx_execve(path, argv, envp);
	return -1;
}

enum { ExecL, ExecLE, ExecLP };

static int exec_as_script(const char *buf, char *const *argv, char *const *envp) {
	size_t arg_count = 1;
	while (argv[arg_count] != NULL)
		++arg_count;

	const char *script_argv[arg_count + 2];
	script_argv[0] = "sh";
	script_argv[1] = buf;
	memcpy(script_argv + 2, argv + 1, arg_count * sizeof(char *));
	return execve(_PATH_BSHELL, (char **const)script_argv, envp);
}

EXPORT int execv(const char *name, char *const *argv) { return execve(name, argv, environ); }

EXPORT int execvp(const char *name, char *const *argv) { return execvpe(name, argv, environ); }

EXPORT int execvpe(const char *name, char *const *argv, char *const *envp) {
	if (name == NULL || *name == '\0') {
		errno = ENOENT;
		return -1;
	}

	// If it's an absolute or relative path name, it's easy.
	if (strchr(name, '/') && execve(name, argv, envp) == -1) {
		if (errno == ENOEXEC) return exec_as_script(name, argv, envp);
		return -1;
	}

	// Get the path we're searching.
	const char *path = getenv("PATH");
	if (path == NULL) path = _PATH_DEFPATH;

	// Make a writable copy.
	size_t len = strlen(path) + 1;
	char writable_path[len];
	memcpy(writable_path, path, len);

	bool saw_EACCES = false;

	// Try each element of $PATH in turn...
	char *strsep_buf = writable_path;
	const char *dir;
	while ((dir = strsep(&strsep_buf, ":"))) {
		// It's a shell path: double, leading and trailing colons
		// mean the current directory.
		if (*dir == '\0') dir = ".";

		size_t dir_len = strlen(dir);
		size_t name_len = strlen(name);

		char buf[dir_len + 1 + name_len + 1];
		mempcpy(mempcpy(mempcpy(buf, dir, dir_len), "/", 1), name, name_len + 1);

		execve(buf, argv, envp);
		switch (errno) {
		case EISDIR:
		case ELOOP:
		case ENAMETOOLONG:
		case ENOENT:
		case ENOTDIR:
			break;
		case ENOEXEC:
			return exec_as_script(buf, argv, envp);
		case EACCES:
			saw_EACCES = true;
			break;
		default:
			return -1;
		}
	}
	if (saw_EACCES) errno = EACCES;
	return -1;
}

static int execl_variant(int variant, const char *name, const char *argv0, va_list ap) {
	// Count the arguments.
	va_list count_ap;
	va_copy(count_ap, ap);
	size_t n = 1;
	while (va_arg(count_ap, char *) != NULL) {
		++n;
	}
	va_end(count_ap);

	// Construct the new argv.
	char *argv[n + 1];
	argv[0] = (char *)argv0;
	n = 1;
	while ((argv[n] = va_arg(ap, char *)) != NULL) {
		++n;
	}

	// Collect the argp too.
	char **argp = (variant == ExecLE) ? va_arg(ap, char **) : environ;

	return (variant == ExecLP) ? execvp(name, argv) : execve(name, argv, argp);
}

EXPORT int execl(const char *name, const char *arg, ...) {
	va_list ap;
	va_start(ap, arg);
	int result = execl_variant(ExecL, name, arg, ap);
	va_end(ap);
	return result;
}

EXPORT int execle(const char *name, const char *arg, ...) {
	va_list ap;
	va_start(ap, arg);
	int result = execl_variant(ExecLE, name, arg, ap);
	va_end(ap);
	return result;
}

EXPORT int execlp(const char *name, const char *arg, ...) {
	va_list ap;
	va_start(ap, arg);
	int result = execl_variant(ExecLP, name, arg, ap);
	va_end(ap);
	return result;
}

EXPORT int fexecve(int fd, char *const *argv, char *const *envp) {
	char buf[40];
	snprintf(buf, sizeof(buf), "/proc/self/fd/%d", fd);
	execve(buf, argv, envp);
	if (errno == ENOENT) errno = EBADF;
	return -1;
}

typedef int spawn_fn(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
                     char *const[], char *const[]);

/* Whether the path, or with search any PATH entry, is relative. */
static bool relative(int search, const char *path, const char *search_path) {
	if (!search || strchr(path, '/')) return path[0] != '/';
	for (const char *p = search_path;; p++) {
		if (*p != '/') return true;
		p = strchr(p, ':');
		if (!p) return false;
	}
}

/* The path is searched and routed here, so the child bionic forks execs an
 * already-routed path; routing that again, should the child's execve reach this
 * library, changes nothing. */
static int spawn_routed(int search, pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                        const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
	static spawn_fn *real, *real_p;
	struct hx_exec x;
	const char *search_path;
	int e;
	if (!real) real = (spawn_fn *)dlsym(RTLD_NEXT, "posix_spawn");
	if (!real_p) real_p = (spawn_fn *)dlsym(RTLD_NEXT, "posix_spawnp");
	if (!real || !real_p) return ENOSYS;
	if (!path) return EFAULT;
	if (!envp) envp = environ;
	search_path = getenv("PATH");
	if (!search_path) search_path = _PATH_DEFPATH;
	/* Bionic's file actions are opaque, so a chdir among them cannot be seen
	 * from here, and a relative path would be resolved in the wrong directory.
	 * With file actions present such a call is handed on as asked; the child
	 * resolves it where it ends up, and routes it there should its execve reach
	 * this library. */
	if (fa && relative(search, path, search_path)) return (search ? real_p : real)(pid, path, fa, attr, argv, envp);
	e = search ? hx_route_search(path, search_path, argv, envp, NULL, &x) : hx_route(path, argv, envp, NULL, &x);
	if (e) return e;
	e = real(pid, x.path, fa, attr, x.argv, x.envp);
	hx_release(&x);
	return e;
}

EXPORT int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                       const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
	return spawn_routed(0, pid, path, fa, attr, argv, envp);
}

EXPORT int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                        const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
	return spawn_routed(1, pid, file, fa, attr, argv, envp);
}

static ssize_t answer(const char *exe, char *buf, size_t size) {
	size_t n = strlen(exe);
	if (n > size) n = size;
	memcpy(buf, exe, n);
	return (ssize_t)n;
}

EXPORT ssize_t readlink(const char *restrict path, char *restrict buf, size_t size) {
	char exe[PATH_MAX];
	if (path && hx_proc_exe(path, exe)) return answer(exe, buf, size);
	return syscall(SYS_readlinkat, AT_FDCWD, path, buf, size);
}

EXPORT ssize_t readlinkat(int dirfd, const char *restrict path, char *restrict buf, size_t size) {
	char exe[PATH_MAX];
	if (path && hx_proc_exe(path, exe)) return answer(exe, buf, size);
	return syscall(SYS_readlinkat, dirfd, path, buf, size);
}

EXPORT char *realpath(const char *restrict path, char *restrict resolved) {
	static char *(*real)(const char *, char *);
	char exe[PATH_MAX];
	if (!real) real = (char *(*)(const char *, char *))dlsym(RTLD_NEXT, "realpath");
	if (!real) {
		errno = ENOSYS;
		return NULL;
	}
	if (path && hx_proc_exe(path, exe)) path = exe;
	return real(path, resolved);
}
