/*
 * Stands in for libsyscallshim.so in musl-e2e.sh: runs its arguments the way the
 * shim does under libtermuxexec.so, through the router, without tracing.
 */
#include <stdio.h>
#include <string.h>

#include "../harness-exec.h"

int main(int argc, char **argv, char **envp) {
	int e;
	if (argc < 2) return 2;
	hx_capture(envp, NULL);
	e = hx_execve(argv[1], argv + 1, envp);
	fprintf(stderr, "fake shim: %s: %s\n", argv[1], strerror(-e));
	return 127;
}
