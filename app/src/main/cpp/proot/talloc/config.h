/*
 * The configuration talloc.c and lib/replace/replace.h read, for talloc 2.4.3
 * on bionic and on glibc. It names the headers and functions talloc.c uses,
 * all of which both C libraries have, so replace.h substitutes none of them
 * and talloc.c links against the C library alone.
 */
#ifndef TALLOC_CONFIG_H
#define TALLOC_CONFIG_H

/* replace.h requires it; it is read before any C library header.  */
#define __STDC_WANT_LIB_EXT1__ 1

#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 3

#define HAVE_ASSERT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_STDARG_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_STDDEF_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_DLFCN_H 1

#define HAVE_BOOL 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_VISIBILITY_ATTR 1

#define HAVE_GETAUXVAL 1
#define HAVE_MEMMOVE 1
#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STRNLEN 1
#define HAVE_USLEEP 1
#define HAVE_VSNPRINTF 1
#define HAVE_C99_VSNPRINTF 1

#endif
