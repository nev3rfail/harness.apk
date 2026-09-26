/*
 * Exec routing: turns every exec into one the platform allows for an app whose
 * data directory may be mapped executable but not execve'd.
 *
 * The same source is compiled into libtermuxexec.so, a bionic LD_PRELOAD, and
 * into the musl libc that is libmuslloader.so. The musl build defines HX_MUSL.
 */
#ifndef HARNESS_EXEC_H
#define HARNESS_EXEC_H

#include <limits.h>
#include <stddef.h>
#include <sys/types.h>

#ifdef HX_MUSL
#define HX_API __attribute__((__visibility__("hidden")))
#else
#define HX_API
#endif

/* Shebangs followed, and the most the kernel reads of a shebang line. */
#define HX_DEPTH 4
#define HX_HEADER 256
/* An interpreter from a shebang line, after /usr/bin is rewritten under the
 * prefix. */
#define HX_INTERP_PATH 512
/* Room inside struct hx_exec for the new argument and environment arrays, one
 * pointer per argument and per environment entry, and the entries the router
 * writes. More than this takes a mapping. */
#ifndef HX_INLINE
#define HX_INLINE 8192
#endif

/* The working state of one decision. */
struct hx_scratch {
	char path[PATH_MAX];
	char interp[HX_DEPTH][HX_INTERP_PATH];
	char header[HX_DEPTH][HX_HEADER];
	char elf_interp[HX_HEADER];
	/* In turn: the rewrite, the ELF header, the realpath, then the
	 * normalized path or TERMUX_EXEC__PROC_SELF_EXE. */
	char work[PATH_MAX];
	const char *head[2 * HX_DEPTH + 2];
};

/* One routed exec, kept by the caller for as long as the exec or spawn it
 * describes: path, argv and envp point into it, or into map when the arrays did
 * not fit inline. hx_release unmaps that once the exec has failed or the spawn
 * has returned.
 *
 * It lives on the caller's stack, 19832 bytes on LP64, because a vfork child
 * shares the parent's address space: a mapping made there and followed by a
 * successful exec stays in the parent for good. Only arrays larger than
 * HX_INLINE still take one: around a thousand arguments and environment
 * entries together. The unit tests measure the deepest stack a routing vfork
 * child reaches, a PATH search included, at 24672 bytes on x86_64. */
struct hx_exec {
	const char *path;
	char **argv;
	char **envp;
	void *map;
	size_t len;
	struct hx_scratch s;
	char *area[HX_INLINE / sizeof(char *)];
};

/* Records the configuration from the process's own start: HARNESS_PREFIX,
 * HARNESS_ETC and TERMUX_EXEC__PROC_SELF_EXE from envp, and nativeLibraryDir as
 * libdir, or the directory of /proc/self/exe when libdir is null. */
HX_API void hx_capture(char *const envp[], const char *libdir);

/* Decides how to run path. base, when not null, is the directory a relative path
 * is resolved against in place of the working directory. Returns 0 or an errno
 * value. Safe in a vfork child. */
HX_API int hx_route(const char *path, char *const argv[], char *const envp[], const char *base,
                    struct hx_exec *out);

/* hx_route of the first entry of search (a PATH value) holding file, with
 * execvp's rules: a name with a slash is not searched, EACCES is reported only
 * when nothing was found, and any error other than ENOENT, ENOTDIR and EACCES
 * ends the search. */
HX_API int hx_route_search(const char *file, const char *search, char *const argv[], char *const envp[],
                           const char *base, struct hx_exec *out);

HX_API void hx_release(struct hx_exec *x);

/* Routes and issues the execve system call. Returns a negative errno value. */
HX_API int hx_execve(const char *path, char *const argv[], char *const envp[]);

/* For /proc/self/exe and /proc/<pid>/exe of a process started through a loader,
 * the program it was started as, resolved, in buf (PATH_MAX bytes). Null
 * otherwise, and for a null path. */
HX_API const char *hx_proc_exe(const char *path, char *buf);

/* A name under /etc/ mapped under $HARNESS_ETC into buf (PATH_MAX bytes), or
 * name itself. */
HX_API const char *hx_etc_path(const char *name, char *buf);

/* Applies one chdir to dir (PATH_MAX bytes, empty for the working directory):
 * to path when it is not null, else to the directory open as fd. Returns 0 or
 * an errno value. */
HX_API int hx_chdir(char *dir, const char *path, int fd);

#endif
