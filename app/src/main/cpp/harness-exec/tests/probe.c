/*
 * A musl program for musl-e2e.sh: reports what it sees of itself, and starts
 * other programs through each of the calls the patched libc routes.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <limits.h>
#include <netdb.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

static int wait_for(pid_t pid) {
	int status;
	if (waitpid(pid, &status, 0) != pid) return 1;
	return WIFEXITED(status) ? WEXITSTATUS(status) : 128;
}

int main(int argc, char **argv) {
	const char *mode = argc > 1 ? argv[1] : "";
	pid_t pid;
	int e;

	if (!strcmp(mode, "exe")) {
		char link[PATH_MAX] = "", real[PATH_MAX] = "";
		ssize_t n = readlink("/proc/self/exe", link, sizeof link - 1);
		const char *pse = getenv("TERMUX_EXEC__PROC_SELF_EXE"), *pre = getenv("LD_PRELOAD");
		link[n > 0 ? n : 0] = 0;
		if (!realpath("/proc/self/exe", real)) strcpy(real, strerror(errno));
		printf("argv0=%s readlink=%s realpath=%s pse=%s preload=%s prefix=%s\n", argv[0], link, real, pse ? pse : "",
		       pre ? pre : "", getenv("HARNESS_PREFIX") ? getenv("HARNESS_PREFIX") : "");
		return 0;
	}
	/* spawn PATH ARGV0 ARGS... / spawnp NAME ARGV0 ARGS... / spawnenv PATH ARGV0 ARGS... */
	if (!strcmp(mode, "spawn") || !strcmp(mode, "spawnp") || !strcmp(mode, "spawnenv")) {
		char *none[] = {NULL};
		e = (mode[5] == 'p' ? posix_spawnp : posix_spawn)(&pid, argv[2], NULL, NULL, argv + 3,
		                                                  mode[5] == 'e' ? none : environ);
		if (e) {
			printf("spawn: %s\n", strerror(e));
			return 1;
		}
		return wait_for(pid);
	}
	/* spawndir DIR RELPATH ARGV0 ARGS...: addchdir to DIR
	 * spawnfd DIR RELPATH ARGV0 ARGS...: addfchdir to DIR open in the parent
	 * spawnopen DIR RELPATH ARGV0 ARGS...: addopen of DIR, then addfchdir to it
	 * spawnrelopen DIR SUB RELPATH ARGV0 ARGS...: addchdir to DIR, addopen of
	 *   SUB relative to it, then addfchdir to that */
	if (!strcmp(mode, "spawndir") || !strcmp(mode, "spawnfd") || !strcmp(mode, "spawnopen") ||
	    !strcmp(mode, "spawnrelopen")) {
		posix_spawn_file_actions_t fa;
		char **rest = argv + 3;
		posix_spawn_file_actions_init(&fa);
		if (!strcmp(mode, "spawndir")) {
			posix_spawn_file_actions_addchdir_np(&fa, argv[2]);
		} else if (!strcmp(mode, "spawnfd")) {
			posix_spawn_file_actions_addfchdir_np(&fa, open(argv[2], O_RDONLY | O_DIRECTORY));
		} else if (!strcmp(mode, "spawnopen")) {
			posix_spawn_file_actions_addopen(&fa, 40, argv[2], O_RDONLY | O_DIRECTORY, 0);
			posix_spawn_file_actions_addfchdir_np(&fa, 40);
		} else {
			posix_spawn_file_actions_addchdir_np(&fa, argv[2]);
			posix_spawn_file_actions_addopen(&fa, 40, argv[3], O_RDONLY | O_DIRECTORY, 0);
			posix_spawn_file_actions_addfchdir_np(&fa, 40);
			rest++;
		}
		e = posix_spawn(&pid, rest[0], &fa, NULL, rest + 1, environ);
		if (e) {
			printf("spawn: %s\n", strerror(e));
			return 1;
		}
		return wait_for(pid);
	}
	if (!strcmp(mode, "exec")) {
		execv(argv[2], argv + 3);
		printf("exec: %s\n", strerror(errno));
		return 1;
	}
	if (!strcmp(mode, "execvp")) {
		execvp(argv[2], argv + 3);
		printf("execvp: %s\n", strerror(errno));
		return 1;
	}
	if (!strcmp(mode, "system")) return WEXITSTATUS(system(argv[2]));
	if (!strcmp(mode, "nullreadlink")) {
		char b[16];
		const char *none = NULL;
		int r = readlink(none, b, sizeof b) < 0 && errno == EFAULT;
		int s = readlinkat(AT_FDCWD, none, b, sizeof b) < 0 && errno == EFAULT;
		printf("%s %s\n", r ? "EFAULT" : "?", s ? "EFAULT" : "?");
		return 0;
	}
	if (!strcmp(mode, "hosts")) {
		struct addrinfo *ai, hints = {.ai_family = AF_INET};
		char b[INET_ADDRSTRLEN];
		if ((e = getaddrinfo(argv[2], NULL, &hints, &ai))) {
			printf("getaddrinfo: %s\n", gai_strerror(e));
			return 1;
		}
		inet_ntop(AF_INET, &((struct sockaddr_in *)ai->ai_addr)->sin_addr, b, sizeof b);
		printf("%s\n", b);
		return 0;
	}
	if (!strcmp(mode, "ifaddrs")) {
		struct ifaddrs *ifa, *i;
		int n = 0;
		if (getifaddrs(&ifa)) {
			printf("getifaddrs: %s\n", strerror(errno));
			return 1;
		}
		for (i = ifa; i; i = i->ifa_next)
			n += i->ifa_addr != NULL;
		freeifaddrs(ifa);
		printf("%d\n", n);
		return 0;
	}
	fprintf(stderr, "unknown mode %s\n", mode);
	return 2;
}
