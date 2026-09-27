/*
 * An LD_PRELOAD for the proot host tests: setsid(3) waits 2 s before the
 * system call, which holds the tracer between vpid 1's reap and its own
 * session.
 */
#define _GNU_SOURCE
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

pid_t setsid(void) {
	struct timespec left = { 2, 0 };

	while (nanosleep(&left, &left) != 0)
		;
	return syscall(SYS_setsid);
}
