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

#
# Graphics capability probe
# -------------------------
# Which route to the GPU exists on this device, which nothing in the Java API
# can answer: Mesa's Panfrost wants a DRM render node (/dev/dri/renderD*), while
# the community PanVK builds for Mali-G52 go through Arm's kbase node
# (/dev/mali0) because those devices ship the closed driver and no DRM node.
# Which one is present decides which graphics backends are worth offering.
#
add_executable(graphics_probe graphics_probe.c)

set_target_properties(graphics_probe PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
)

target_link_libraries(graphics_probe PRIVATE ${CMAKE_DL_LIBS} log)

#
# Process management probe
# ------------------------
# Wine does not run one process, it runs a tree, and Android 12+ kills an app's
# children past a fixed cap — the victim vanishing with no exit status. This
# measures fork/exec/wait round trips, whether a small tree can be reaped
# cleanly, and what the process limit actually is on this device.
#
add_executable(process_probe process_probe.c)

set_target_properties(process_probe PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
)

target_link_libraries(process_probe PRIVATE log)
