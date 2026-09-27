/*
 * Calls libandroid-shmem's shmget for a keyed segment and prints the result
 * as "ret=N errno=NAME". Linked against the patched shmem.c for the proot
 * host tests. A host has no /dev/ashmem, so a call that gets past publishing
 * the key still fails; the test reads the key's symlink instead.
 */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/ipc.h>

int libandroid_shmget(key_t key, size_t size, int flags);

int main(void) {
	int ret;

	errno = 0;
	ret = libandroid_shmget(0x1234, 4096, IPC_CREAT | 0600);
	printf("ret=%d errno=%s\n", ret, errno == ENOENT ? "ENOENT" : strerror(errno));
	return 0;
}
