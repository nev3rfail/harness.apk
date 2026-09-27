/*
 * A program for the proot host tests.
 *
 *   probe                   prints TAG, its arguments and /proc/self/exe
 *   probe exec PATH ARG...  execve(2)s PATH with ARG... as its argv; on failure
 *                           prints the name of the errno and exits 1
 */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#ifndef TAG
#define TAG "probe"
#endif

extern char **environ;

static const char *errno_name(int e) {
	switch (e) {
	case ENOENT: return "ENOENT";
	case ENOEXEC: return "ENOEXEC";
	case EACCES: return "EACCES";
	case EINVAL: return "EINVAL";
	default: return strerror(e);
	}
}

int main(int argc, char **argv) {
	char exe[4096];
	ssize_t n;
	int i;

	if (argc >= 3 && !strcmp(argv[1], "exec")) {
		execve(argv[2], argv + 3, environ);
		printf("%s\n", errno_name(errno));
		return 1;
	}

	n = readlink("/proc/self/exe", exe, sizeof exe - 1);
	exe[n < 0 ? 0 : n] = 0;
	printf("tag=%s exe=%s", TAG, exe);
	for (i = 0; i < argc; i++)
		printf(" [%s]", argv[i]);
	printf("\n");
	return 0;
}
