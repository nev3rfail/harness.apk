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
