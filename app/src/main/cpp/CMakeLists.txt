cmake_minimum_required(VERSION 3.22.1)
project("harness" C)

# A program, not a library, but it ships as one: only the native library
# directory is executable no matter which API level the app targets, and only
# files named lib*.so are installed there.
add_executable(relocate relocate.c)
set_target_properties(relocate PROPERTIES
    OUTPUT_NAME "relocate"
    PREFIX "lib"
    SUFFIX ".so")

# Exec routing for bionic programs, preloaded into each of them. The musl side is
# the same router compiled into the loader by scripts/stage-claude.sh.
add_library(termuxexec SHARED
    harness-exec/harness-exec.c
    harness-exec/interpose.c)
set_target_properties(termuxexec PROPERTIES C_VISIBILITY_PRESET hidden)
target_compile_options(termuxexec PRIVATE -Wall -Wextra -Werror)
target_link_options(termuxexec PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(termuxexec dl)
