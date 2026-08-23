// Enumerates which syscalls Android's seccomp policy refuses to an app.
//
// Each candidate runs in its own forked child with all-zero arguments, chosen
// to reach the kernel and fail harmlessly. The interesting outcome is how the
// child dies rather than what it returns: killed by SIGSYS means the policy
// blocks the call, and any errno at all means the policy let it through. An
// alarm covers the calls that would otherwise block forever.
//
// Build for the device's ABI and run it from inside the app, not through
// `run-as`: `run-as` puts the process in a different SELinux domain with a
// different filter, which is the thing being measured.
#define _GNU_SOURCE
#include <stdio.h>
#include <signal.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/wait.h>

#define MAX_SYSCALL 462

// Calls that would end the scan rather than report on it: they replace the
// process image, kill something, take the machine down, or need a tracer.
static int destructive(long nr) {
    switch (nr) {
    case 56:  /* clone */
    case 57:  /* fork */
    case 58:  /* vfork */
    case 59:  /* execve */
    case 60:  /* exit */
    case 62:  /* kill */
    case 101: /* ptrace */
    case 169: /* reboot */
    case 200: /* tkill */
    case 231: /* exit_group */
    case 234: /* tgkill */
    case 246: /* kexec_load */
    case 322: /* execveat */
    case 435: /* clone3 */
        return 1;
    default:
        return 0;
    }
}

int main(void) {
    int blocked = 0, skipped = 0;

    for (long nr = 0; nr <= MAX_SYSCALL; nr++) {
        if (destructive(nr)) { skipped++; continue; }

        pid_t pid = fork();
        if (pid < 0) { perror("fork"); return 1; }
        if (pid == 0) {
            alarm(1);
            syscall(nr, 0L, 0L, 0L, 0L, 0L, 0L);
            _exit(0);
        }

        int status;
        waitpid(pid, &status, 0);
        if (WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS) {
            printf("%ld\n", nr);
            blocked++;
        }
    }

    printf("== %d blocked, %d skipped as destructive, of %d\n",
           blocked, skipped, MAX_SYSCALL + 1);
    return 0;
}
