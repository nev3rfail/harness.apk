// Rewrites Termux's compiled-in paths across a tree.
//
//   librelocate --flock <fd>
//   librelocate [--lock <file>] [--stamp <file> <version>] [--content] [--links]
//               [--tls] [--md5sums <dir>] <tree> [<old> <new>]...
//
// --content replaces every <old> in file contents byte for byte. A stripped
// binary carries no relocation information, so a replacement has to be exactly
// as long as what it replaces: a shorter <new> is padded with slashes, which the
// kernel and the linker collapse, and a longer one is left out of this pass.
//
// --links repoints each symlink whose target starts with an <old> on a component
// boundary, longest <old> first, after any run of leading slashes. A target is
// a string in the link's own inode rather than bytes in a file, so it takes a
// <new> of any length, unpadded.
//
// --tls lowers a PT_TLS alignment larger than the largest alignment of the TLS
// sections back to it, in x86_64 executables (ET_EXEC or ET_DYN with a PT_INTERP)
// that have section headers, and leaves every other file alone.
// termux-elf-cleaner raises it to 64, and x86_64 places an executable's TLS below
// the thread pointer at an offset rounded to that alignment, so the program reads
// zeroed memory where its initial TLS belongs. bionic on arm64 requires 64.
//
// --md5sums rewrites the entries in <dir>/*.md5sums for the x86_64 files --tls
// selects, realigned in this run or an earlier one, so a run that stopped before
// this step is completed by the next. An entry names a file by its path without
// the leading slash, either as it is or under an <old> the pairs map to it.
//
// An entry the walk cannot read, or one that is gone by the time it is reached,
// is logged and passed over rather than counted as a failure.
//
// --lock takes flock(2) on <file>, polling for 30 s before it gives up with
// status 75. --stamp skips the run when <file> holds <version>, and writes
// <version> there after a run without failures; both happen under the lock.
//
// --flock takes flock(2) on the open file description behind <fd> and returns,
// so the lock lasts as long as the caller holds <fd>.
//
// Every failure is counted, and any count above zero makes the exit status 1:
// a caller that cannot tell a partial run from a finished one will install a
// half-relocated tree and report success.
#define _GNU_SOURCE
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOCK_BUSY 75
#define LOCK_POLLS 300          // 30 s at 100 ms
#define MAX_PAIRS 16

struct pair {
    const char *old, *new;
    size_t old_len, new_len;
    char *padded;               // <new> padded to <old>'s length, or NULL when it does not fit
};

static struct pair pairs[MAX_PAIRS];
static int npairs;
static int do_content, do_links, do_tls, do_md5sums;
static long touched, hits, links, realigned, skipped, failed;

// Counts an entry that could not be opened: a skip when it is unreadable or
// gone, a failure otherwise.
static void unopened(const char *path) {
    if (errno == EACCES || errno == ENOENT) {
        fprintf(stderr, "%s: %s, skipped\n", path, strerror(errno));
        skipped++;
    } else {
        fprintf(stderr, "%s: %s\n", path, strerror(errno));
        failed++;
    }
}

// The files --tls selected, by their real path without the leading slash, with
// the checksum of each.
struct changed { char *key; char md5[33]; };
static struct changed *changes;
static size_t nchanges;

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

static int read_at(int fd, void *buf, size_t len, off_t at) {
    return pread(fd, buf, len, at) == (ssize_t)len ? 0 : -1;
}

// ---- MD5 (RFC 1321) -------------------------------------------------------

struct md5 { uint32_t h[4]; uint64_t len; unsigned char buf[64]; size_t used; };

static const uint32_t MD5_K[64] = {
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
};
static const unsigned char MD5_S[64] = {
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
};

static void md5_block(struct md5 *m, const unsigned char *p) {
    uint32_t w[16];
    for (int i = 0; i < 16; i++)
        w[i] = (uint32_t)p[i * 4] | (uint32_t)p[i * 4 + 1] << 8 |
               (uint32_t)p[i * 4 + 2] << 16 | (uint32_t)p[i * 4 + 3] << 24;
    uint32_t a = m->h[0], b = m->h[1], c = m->h[2], d = m->h[3];
    for (int i = 0; i < 64; i++) {
        uint32_t f;
        int g;
        if (i < 16) { f = (b & c) | (~b & d); g = i; }
        else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) % 16; }
        else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
        else { f = c ^ (b | ~d); g = (7 * i) % 16; }
        f += a + MD5_K[i] + w[g];
        a = d;
        d = c;
        c = b;
        b += f << MD5_S[i] | f >> (32 - MD5_S[i]);
    }
    m->h[0] += a; m->h[1] += b; m->h[2] += c; m->h[3] += d;
}

static void md5_update(struct md5 *m, const unsigned char *p, size_t len) {
    m->len += len;
    while (len > 0) {
        size_t n = 64 - m->used < len ? 64 - m->used : len;
        memcpy(m->buf + m->used, p, n);
        m->used += n;
        p += n;
        len -= n;
        if (m->used == 64) { md5_block(m, m->buf); m->used = 0; }
    }
}

// The hex digest of the file at [path], or -1.
static int md5_file(const char *path, char out[33]) {
    struct md5 m = { { 0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476 }, 0, { 0 }, 0 };
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    unsigned char buf[65536];
    ssize_t n;
    while ((n = read(fd, buf, sizeof buf)) > 0) md5_update(&m, buf, (size_t)n);
    close(fd);
    if (n < 0) return -1;
    uint64_t bits = m.len * 8;
    unsigned char pad = 0x80, zero = 0, tail[8];
    md5_update(&m, &pad, 1);
    while (m.used != 56) md5_update(&m, &zero, 1);
    for (int i = 0; i < 8; i++) tail[i] = (unsigned char)(bits >> (8 * i));
    md5_update(&m, tail, 8);
    for (int i = 0; i < 16; i++)
        snprintf(out + i * 2, 3, "%02x", (unsigned)(m.h[i / 4] >> (8 * (i % 4))) & 0xff);
    return 0;
}

// ---- content --------------------------------------------------------------

static void content_file(const char *path, mode_t mode) {
    int fd = open(path, O_RDWR | O_CLOEXEC);
    int restored = 0;
    if (fd < 0) {                     // a read-only file still has to be patched
        if (chmod(path, mode | S_IWUSR) != 0) { failed++; return; }
        fd = open(path, O_RDWR | O_CLOEXEC);
        if (fd < 0) { chmod(path, mode); failed++; return; }
        restored = 1;
    }
    struct stat st;
    if (fstat(fd, &st) != 0) { failed++; goto done; }
    if (st.st_size <= 0) goto done;
    size_t size = (size_t)st.st_size;
    char *buf = malloc(size);
    if (buf == NULL) { failed++; goto done; }
    if (read_all(fd, buf, size) != 0) { failed++; free(buf); goto done; }
    long found = 0;
    for (size_t i = 0; i < size; i++) {
        for (int p = 0; p < npairs; p++) {
            const struct pair *pr = &pairs[p];
            if (pr->padded == NULL || buf[i] != pr->old[0] || size - i < pr->old_len) continue;
            if (memcmp(buf + i, pr->old, pr->old_len) != 0) continue;
            // Only the span that changed is written. The scan already knows where
            // it is, and rewriting whole files for one hit each is the difference
            // between kilobytes and ninety megabytes.
            if (write_at(fd, pr->padded, pr->old_len, (off_t)i) != 0) {
                failed++;
                free(buf);
                goto done;
            }
            found++;
            i += pr->old_len - 1;
            break;
        }
    }
    if (found > 0) { touched++; hits += found; }
    free(buf);
done:
    close(fd);
    if (restored) chmod(path, mode);
}

// ---- links ----------------------------------------------------------------

static void relink(const char *path) {
    char target[PATH_MAX];
    ssize_t n = readlink(path, target, sizeof target - 1);
    if (n <= 0) { failed++; return; }
    target[n] = '\0';

    // A run of leading slashes names the same path as one, and some packages
    // ship targets spelled `//data/...`.
    const char *t = target;
    while (t[0] == '/' && t[1] == '/') t++;

    // Longest <old> first, since the pairs are sorted that way.
    const struct pair *pr = NULL;
    for (int p = 0; p < npairs && pr == NULL; p++) {
        const char *rest = t + pairs[p].old_len;
        if (strncmp(t, pairs[p].old, pairs[p].old_len) == 0 && (*rest == '/' || *rest == '\0'))
            pr = &pairs[p];
    }
    if (pr == NULL) return;

    char fixed[PATH_MAX];
    if (snprintf(fixed, sizeof fixed, "%s%s", pr->new, t + pr->old_len) >= (int)sizeof fixed) {
        failed++;
        return;
    }

    // Created beside the link and renamed over it. Unlinking first would mean a
    // failed symlink leaves nothing where a link used to be.
    char tmp[PATH_MAX + 16];
    snprintf(tmp, sizeof tmp, "%s.relocating", path);
    unlink(tmp);
    if (symlink(fixed, tmp) != 0) { failed++; return; }
    if (rename(tmp, path) != 0) { unlink(tmp); failed++; return; }
    links++;
}

// ---- TLS ------------------------------------------------------------------

static void record_change(const char *path) {
    struct changed *grown = realloc(changes, (nchanges + 1) * sizeof *changes);
    if (grown == NULL) { failed++; return; }
    changes = grown;
    struct changed *c = &changes[nchanges];
    c->key = strdup(path[0] == '/' ? path + 1 : path);
    if (c->key == NULL || md5_file(path, c->md5) != 0) { free(c->key); failed++; return; }
    nchanges++;
}

// Writes a copy of [fd] beside [path] with the program header at [at] replaced
// by [ph], and renames it over [path]. The file may be a running program, which
// the kernel does not let anything write to.
static int replace_phdr(const char *path, int fd, const struct stat *st,
                        const Elf64_Phdr *ph, off_t at) {
    char tmp[PATH_MAX + 16];
    if (snprintf(tmp, sizeof tmp, "%s.relocating", path) >= (int)sizeof tmp) return -1;
    unlink(tmp);
    int out = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (out < 0) return -1;
    char buf[65536];
    off_t off = 0;
    ssize_t n;
    while ((n = pread(fd, buf, sizeof buf, off)) > 0) {
        if (write_at(out, buf, (size_t)n, off) != 0) goto fail;
        off += n;
    }
    if (n < 0 || off != st->st_size) goto fail;
    if (write_at(out, (const char *)ph, sizeof *ph, at) != 0) goto fail;
    if (fchmod(out, st->st_mode & 07777) != 0 || fsync(out) != 0) goto fail;
    if (close(out) != 0) { out = -1; goto fail; }
    if (rename(tmp, path) != 0) { unlink(tmp); return -1; }
    return 0;
fail:
    if (out >= 0) close(out);
    unlink(tmp);
    return -1;
}

static void tls_file(const char *path, const struct stat *st) {
    if (st->st_size < (off_t)sizeof(Elf64_Ehdr)) return;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { unopened(path); return; }
    Elf64_Ehdr eh;
    Elf64_Phdr *ph = NULL;
    Elf64_Shdr *sh = NULL;
    if (read_at(fd, &eh, sizeof eh, 0) != 0) { failed++; goto done; }
    if (memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 || eh.e_ident[EI_CLASS] != ELFCLASS64 ||
        eh.e_ident[EI_DATA] != ELFDATA2LSB || eh.e_machine != EM_X86_64 ||
        (eh.e_type != ET_EXEC && eh.e_type != ET_DYN) ||
        eh.e_phentsize != sizeof *ph || eh.e_phnum == 0 ||
        eh.e_shoff == 0 || eh.e_shnum == 0 || eh.e_shentsize != sizeof *sh)
        goto done;

    ph = calloc(eh.e_phnum, sizeof *ph);
    if (ph == NULL || read_at(fd, ph, eh.e_phnum * sizeof *ph, (off_t)eh.e_phoff) != 0) {
        failed++;
        goto done;
    }
    int interp = 0, tls = -1;
    for (int i = 0; i < eh.e_phnum; i++) {
        if (ph[i].p_type == PT_INTERP) interp = 1;
        if (ph[i].p_type == PT_TLS) tls = i;
    }
    if (!interp || tls < 0) goto done;

    sh = calloc(eh.e_shnum, sizeof *sh);
    if (sh == NULL || read_at(fd, sh, eh.e_shnum * sizeof *sh, (off_t)eh.e_shoff) != 0) {
        failed++;
        goto done;
    }
    Elf64_Xword align = 0;
    for (int i = 0; i < eh.e_shnum; i++)
        if ((sh[i].sh_flags & SHF_TLS) && sh[i].sh_addralign > align) align = sh[i].sh_addralign;
    if (align == 0) goto done;

    if (ph[tls].p_align > align) {
        Elf64_Phdr fixed = ph[tls];
        fixed.p_align = align;
        off_t at = (off_t)(eh.e_phoff + (Elf64_Off)tls * sizeof *ph);
        if (replace_phdr(path, fd, st, &fixed, at) != 0) { failed++; goto done; }
        realigned++;
    }
    if (do_md5sums) record_change(path);
done:
    free(ph);
    free(sh);
    close(fd);
}

// ---- md5sums --------------------------------------------------------------

// True when the entry [name] names [key], as it is or under the first <old> it
// starts with on a component boundary.
static int names(const char *key, const char *name, size_t len) {
    size_t key_len = strlen(key);
    if (key_len == len && memcmp(key, name, len) == 0) return 1;
    for (int p = 0; p < npairs; p++) {
        if (pairs[p].old[0] != '/' || pairs[p].new[0] != '/') continue;
        const char *old = pairs[p].old + 1, *new = pairs[p].new + 1;
        size_t old_len = pairs[p].old_len - 1, new_len = pairs[p].new_len - 1;
        if (len < old_len || memcmp(name, old, old_len) != 0) continue;
        if (len > old_len && name[old_len] != '/') continue;
        return key_len == new_len + len - old_len && memcmp(key, new, new_len) == 0 &&
               memcmp(key + new_len, name + old_len, len - old_len) == 0;
    }
    return 0;
}

static const struct changed *changed_entry(const char *name, size_t len) {
    for (size_t i = 0; i < nchanges; i++)
        if (names(changes[i].key, name, len)) return &changes[i];
    return NULL;
}

// Rewrites the checksum of every entry in [path] that names a changed file,
// through a new file renamed over it.
static void md5sums_file(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) != 0) { if (fd >= 0) close(fd); failed++; return; }
    size_t size = (size_t)st.st_size;
    char *buf = malloc(size + 1);
    if (buf == NULL || (size > 0 && read_all(fd, buf, size) != 0)) {
        close(fd);
        free(buf);
        failed++;
        return;
    }
    close(fd);

    int edited = 0;
    for (size_t line = 0; line < size;) {
        char *end = memchr(buf + line, '\n', size - line);
        size_t len = (end ? (size_t)(end - buf) : size) - line;
        // "<32 hex digits>  <path>"
        if (len > 34 && buf[line + 32] == ' ' && buf[line + 33] == ' ') {
            const struct changed *c = changed_entry(buf + line + 34, len - 34);
            if (c != NULL && memcmp(buf + line, c->md5, 32) != 0) {
                memcpy(buf + line, c->md5, 32);
                edited = 1;
            }
        }
        line += len + 1;
    }
    if (edited) {
        char tmp[PATH_MAX + 16];
        snprintf(tmp, sizeof tmp, "%s.relocating", path);
        int out = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, st.st_mode & 07777);
        if (out < 0 || write_at(out, buf, size, 0) != 0 || fsync(out) != 0 ||
            close(out) != 0 || rename(tmp, path) != 0) {
            if (out >= 0) close(out);
            unlink(tmp);
            failed++;
        }
    }
    free(buf);
}

static void rewrite_md5sums(const char *dir) {
    if (nchanges == 0) return;
    DIR *d = opendir(dir);
    if (d == NULL) { fprintf(stderr, "%s: %s\n", dir, strerror(errno)); failed++; return; }
    struct dirent *entry;
    while ((entry = readdir(d)) != NULL) {
        size_t len = strlen(entry->d_name);
        if (len <= 8 || strcmp(entry->d_name + len - 8, ".md5sums") != 0) continue;
        char path[PATH_MAX];
        if (snprintf(path, sizeof path, "%s/%s", dir, entry->d_name) >= (int)sizeof path) {
            failed++;
            continue;
        }
        md5sums_file(path);
    }
    closedir(d);
}

// ---- walk -----------------------------------------------------------------

static void walk(const char *dir) {
    DIR *d = opendir(dir);
    if (d == NULL) { unopened(dir); return; }
    struct dirent *entry;
    while ((entry = readdir(d)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        char path[PATH_MAX];
        if (snprintf(path, sizeof path, "%s/%s", dir, entry->d_name) >= (int)sizeof path) {
            failed++;
            continue;
        }
        struct stat st;
        if (lstat(path, &st) != 0) { unopened(path); continue; }
        if (S_ISLNK(st.st_mode)) {
            if (do_links) relink(path);
        } else if (S_ISDIR(st.st_mode)) {
            walk(path);
        } else if (S_ISREG(st.st_mode)) {
            if (do_content) content_file(path, st.st_mode);
            if (do_tls) {
                if (do_content && lstat(path, &st) != 0) { failed++; continue; }
                tls_file(path, &st);
            }
        }
    }
    closedir(d);
}

// ---- locks and the stamp --------------------------------------------------

// flock(2) on [file], polled so that a holder that never lets go ends the wait.
static int lock_file(const char *file) {
    int fd = open(file, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) { fprintf(stderr, "%s: %s\n", file, strerror(errno)); return 1; }
    for (int i = 0; i < LOCK_POLLS; i++) {
        if (flock(fd, LOCK_EX | LOCK_NB) == 0) return 0;
        if (errno != EWOULDBLOCK && errno != EINTR) {
            fprintf(stderr, "%s: %s\n", file, strerror(errno));
            return 1;
        }
        usleep(100000);
    }
    fprintf(stderr, "%s: still locked after 30 s\n", file);
    return LOCK_BUSY;
}

static int stamp_current(const char *file, const char *version) {
    char buf[256];
    int fd = open(file, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    buf[strcspn(buf, "\n")] = '\0';
    return strcmp(buf, version) == 0;
}

static void write_stamp(const char *file, const char *version) {
    char tmp[PATH_MAX + 16];
    snprintf(tmp, sizeof tmp, "%s.relocating", file);
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0 || dprintf(fd, "%s\n", version) < 0 || fsync(fd) != 0 || close(fd) != 0 ||
        rename(tmp, file) != 0) {
        fprintf(stderr, "%s: %s\n", file, strerror(errno));
        unlink(tmp);
        failed++;
    }
}

static int by_length(const void *a, const void *b) {
    size_t x = ((const struct pair *)a)->old_len, y = ((const struct pair *)b)->old_len;
    return x < y ? 1 : x > y ? -1 : 0;
}

static int usage(void) {
    fprintf(stderr,
            "usage: librelocate --flock <fd>\n"
            "       librelocate [--lock <file>] [--stamp <file> <version>] [--content]\n"
            "                   [--links] [--tls] [--md5sums <dir>] <tree> [<old> <new>]...\n");
    return 2;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--flock") == 0) {
        char *end;
        long fd = strtol(argv[2], &end, 10);
        if (*argv[2] == '\0' || *end != '\0' || fd < 0 || fd > INT_MAX) return usage();
        while (flock((int)fd, LOCK_EX) != 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "flock %ld: %s\n", fd, strerror(errno));
            return 1;
        }
        return 0;
    }

    const char *lock = NULL, *stamp = NULL, *version = NULL, *md5sums = NULL;
    int i = 1;
    for (; i < argc && strncmp(argv[i], "--", 2) == 0; i++) {
        if (strcmp(argv[i], "--content") == 0) do_content = 1;
        else if (strcmp(argv[i], "--links") == 0) do_links = 1;
        else if (strcmp(argv[i], "--tls") == 0) do_tls = 1;
        else if (strcmp(argv[i], "--lock") == 0 && i + 1 < argc) lock = argv[++i];
        else if (strcmp(argv[i], "--md5sums") == 0 && i + 1 < argc) {
            md5sums = argv[++i];
            do_md5sums = 1;
        }
        else if (strcmp(argv[i], "--stamp") == 0 && i + 2 < argc) {
            stamp = argv[++i];
            version = argv[++i];
        } else return usage();
    }
    if (!do_content && !do_links && !do_tls) return usage();
    if (i >= argc || (argc - i - 1) % 2 != 0 || (argc - i - 1) / 2 > MAX_PAIRS) return usage();
    const char *tree = argv[i++];
    for (; i < argc; i += 2) {
        struct pair *p = &pairs[npairs++];
        p->old = argv[i];
        p->new = argv[i + 1];
        p->old_len = strlen(p->old);
        p->new_len = strlen(p->new);
        if (p->old_len == 0) { fprintf(stderr, "nothing to replace\n"); return 2; }
        if (p->new_len <= p->old_len) {
            p->padded = malloc(p->old_len);
            if (p->padded == NULL) return 1;
            memcpy(p->padded, p->new, p->new_len);
            memset(p->padded + p->new_len, '/', p->old_len - p->new_len);
        }
    }
    if ((do_content || do_links) && npairs == 0) return usage();
    qsort(pairs, (size_t)npairs, sizeof *pairs, by_length);

    if (lock != NULL) {
        int status = lock_file(lock);
        if (status != 0) return status;
    }
    if (stamp != NULL && stamp_current(stamp, version)) {
        printf("%s is at %s\n", stamp, version);
        return 0;
    }

    // Walked by its real path, which is how an md5sums entry names a file.
    char root[PATH_MAX];
    // The tree itself has to be readable; only entries inside it are skipped.
    if (realpath(tree, root) == NULL || access(root, R_OK | X_OK) != 0) {
        fprintf(stderr, "%s: %s\n", tree, strerror(errno));
        return 1;
    }
    walk(root);
    if (md5sums != NULL) rewrite_md5sums(md5sums);
    if (stamp != NULL && failed == 0) write_stamp(stamp, version);

    printf("%ld files rewritten, %ld occurrences, %ld symlinks repointed, "
           "%ld TLS segments realigned, %ld skipped, %ld failed\n",
           touched, hits, links, realigned, skipped, failed);
    return failed == 0 ? 0 : 1;
}
