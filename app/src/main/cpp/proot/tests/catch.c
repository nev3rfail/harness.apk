/*
 * A program for the proot host tests, run as vpid 1.
 *
 *   catch N        blocks the signals below, prints "ready pid=P ppid=Q", then
 *                  prints "sig=S code=C" for each of N signals it receives and
 *                  exits with status N
 *   catch disp     prints whether SIGHUP and SIGINT are ignored
 *   catch shm      creates, attaches and removes a System V shared memory
 *                  segment and prints "shm ok", then waits for end of file
 *                  on stdin
 */
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <unistd.h>

static const int caught[] = {
	SIGHUP, SIGINT, SIGQUIT, SIGUSR1, SIGUSR2, SIGSEGV, SIGTERM, SIGWINCH,
};

static const char *disposition(int signum) {
	struct sigaction old;

	sigaction(signum, NULL, &old);
	return old.sa_handler == SIG_IGN ? "ign" : "dfl";
}

int main(int argc, char **argv) {
	sigset_t set;
	siginfo_t info;
	int i, n;

	if (argc != 2)
		return 2;

	if (!strcmp(argv[1], "disp")) {
		printf("HUP=%s INT=%s\n", disposition(SIGHUP), disposition(SIGINT));
		return 0;
	}

	if (!strcmp(argv[1], "shm")) {
		char c;
		int id = shmget(IPC_PRIVATE, 4096, IPC_CREAT | 0600);
		void *at = id < 0 ? (void *) -1 : shmat(id, NULL, 0);

		if (at == (void *) -1) {
			perror("shm");
			return 1;
		}
		shmdt(at);
		shmctl(id, IPC_RMID, NULL);
		printf("shm ok\n");
		fflush(stdout);
		while (read(0, &c, 1) > 0)
			;
		return 0;
	}

	n = atoi(argv[1]);
	sigemptyset(&set);
	for (i = 0; i < (int) (sizeof caught / sizeof *caught); i++)
		sigaddset(&set, caught[i]);
	sigaddset(&set, SIGRTMIN + 1);
	sigprocmask(SIG_BLOCK, &set, NULL);

	printf("ready pid=%d ppid=%d\n", getpid(), getppid());
	fflush(stdout);
	for (i = 0; i < n; i++) {
		if (sigwaitinfo(&set, &info) < 0)
			return 3;
		printf("sig=%d code=%d\n", info.si_signo, info.si_code);
		fflush(stdout);
	}
	return n;
}
