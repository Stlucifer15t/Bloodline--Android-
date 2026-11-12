cmake_minimum_required(VERSION 3.22.1)

project(native_probe C)

#
# Why this is an executable renamed to libnative_probe.so
# -------------------------------------------------------
# Since Android 10 the W^X policy forbids executing a file out of the writable
# app data directory. The package installer, however, extracts everything under
# lib/<abi>/ into a read-only directory that IS executable, and it only checks
# that the name looks like a shared library.
#
# So: build a normal PIE executable, name it libnative_probe.so, and Android
# will happily extract and run it. This is the same trick Wine, Box86 and the
# rest of the compatibility runtime will rely on, which is exactly what this
# probe exists to prove before any of that code is written.
#
add_executable(native_probe native_probe.c)

set_target_properties(native_probe PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
)

#
# Runtime capability probe
# ------------------------
# Same packaging trick, different question. native_probe proved a bundled
# executable can run at all; this one measures what the *device* allows, which
# decides how the compatibility runtime can be built:
#
#   ptrace          PRoot and every ptrace based rootless container need it
#   seccomp         how tightly the process is already filtered
#   memfd + exec    the likeliest way to launch binaries under Android's W^X
#   32-bit support  Box86, which would run the 32-bit x86 game, is itself 32-bit
#
add_executable(runtime_probe runtime_probe.c)

set_target_properties(runtime_probe PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
)
