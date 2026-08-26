// Rewrites a compiled-in prefix across a tree, byte for byte.
//
// A stripped binary carries no relocation information, so the replacement has to
// be exactly as long as what it replaces. A symlink target is a string in its own
// inode rather than bytes in a file, so it is replaced rather than edited.
//
// Every failure is counted, and any count above zero makes the exit status
// non-zero: a caller that cannot tell a partial run from a finished one will
// install a half-relocated tree and report success.
#define _GNU_SOURCE
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static const char *OLD, *NEW;
static size_t LEN;
static long touched, hits, links, failed;

// A short read or write is allowed by POSIX rather than exceptional, so both are
// driven to completion before anything is counted.
static int read_all(int fd, char *buf, size_t len) {
    while (len > 0) {
        ssize_t n = read(fd, buf, len);
        if (n <= 0) return -1;
        buf += n;
        len -= (size_t)n;
    }
    return 0;
}

static int write_at(int fd, const char *buf, size_t len, off_t at) {
    while (len > 0) {
        ssize_t n = pwrite(fd, buf, len, at);
        if (n <= 0) return -1;
        buf += n;
        len -= (size_t)n;
        at += n;
    }
    return 0;
}

static void do_file(const char *path, mode_t mode) {
    int fd = open(path, O_RDWR);
    int restored = 0;
    if (fd < 0) {                     // a read-only file still has to be patched
        if (chmod(path, mode | S_IWUSR) != 0) { failed++; return; }
        fd = open(path, O_RDWR);
        if (fd < 0) { chmod(path, mode); failed++; return; }
        restored = 1;
    }
    struct stat st;
    if (fstat(fd, &st) != 0) { failed++; goto done; }
    if (st.st_size <= 0) goto done;
    char *buf = malloc((size_t)st.st_size);
    if (buf == NULL) { failed++; goto done; }
    if (read_all(fd, buf, (size_t)st.st_size) != 0) { failed++; free(buf); goto done; }
    long found = 0;
    for (size_t i = 0; i + LEN <= (size_t)st.st_size; i++) {
        if (memcmp(buf + i, OLD, LEN) != 0) continue;
        // Only the span that changed is written. The scan already knows where it
        // is, and rewriting whole files for one hit each is the difference
        // between kilobytes and ninety megabytes.
        if (write_at(fd, NEW, LEN, (off_t)i) != 0) { failed++; free(buf); goto done; }
        found++;
        i += LEN - 1;
    }
    if (found > 0) { touched++; hits += found; }
    free(buf);
done:
    close(fd);
    if (restored) chmod(path, mode);
}

static void do_link(const char *path) {
    char target[4096];
    ssize_t n = readlink(path, target, sizeof target - 1);
    if (n <= 0) { failed++; return; }
    target[n] = '\0';
    if (strstr(target, OLD) == NULL) return;

    // The replacement is the same length, so the rewritten target cannot be
    // longer than what was read.
    char fixed[sizeof target];
    size_t o = 0;
    for (const char *p = target; *p != '\0';) {
        if (strncmp(p, OLD, LEN) == 0) {
            memcpy(fixed + o, NEW, LEN);
            o += LEN;
            p += LEN;
        } else {
            fixed[o++] = *p++;
        }
    }
    fixed[o] = '\0';

    // Created beside the link and renamed over it. Unlinking first would mean a
    // failed symlink leaves nothing where a link used to be.
    char tmp[sizeof target + 16];
    if (snprintf(tmp, sizeof tmp, "%s.relocating", path) >= (int)sizeof tmp) {
        failed++;
        return;
    }
    unlink(tmp);
    if (symlink(fixed, tmp) != 0) { failed++; return; }
    if (rename(tmp, path) != 0) { unlink(tmp); failed++; return; }
    links++;
}

static void walk(const char *dir) {
    DIR *d = opendir(dir);
    if (d == NULL) { failed++; return; }
    struct dirent *entry;
    while ((entry = readdir(d)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        char path[4096];
        if (snprintf(path, sizeof path, "%s/%s", dir, entry->d_name) >= (int)sizeof path) {
            failed++;
            continue;
        }
        struct stat st;
        if (lstat(path, &st) != 0) { failed++; continue; }
        if (S_ISLNK(st.st_mode)) do_link(path);
        else if (S_ISDIR(st.st_mode)) walk(path);
        else if (S_ISREG(st.st_mode)) do_file(path, st.st_mode);
    }
    closedir(d);
}

int main(int argc, char **argv) {
    if (argc != 4) { fprintf(stderr, "usage: relocate <tree> <old> <new>\n"); return 2; }
    OLD = argv[2];
    NEW = argv[3];
    if (strlen(OLD) != strlen(NEW)) {
        fprintf(stderr, "not the same length: %s (%zu) -> %s (%zu)\n",
                OLD, strlen(OLD), NEW, strlen(NEW));
        return 2;
    }
    LEN = strlen(OLD);
    if (LEN == 0) { fprintf(stderr, "nothing to replace\n"); return 2; }
    walk(argv[1]);
    printf("%ld files rewritten, %ld occurrences, %ld symlinks repointed, %ld failed\n",
           touched, hits, links, failed);
    return failed == 0 ? 0 : 1;
}
