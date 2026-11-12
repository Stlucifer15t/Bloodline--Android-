/*
 * Runtime capability probe.
 *
 * Before a Windows compatibility runtime can be designed, someone has to
 * answer questions about the actual device, because the answers decide the
 * architecture:
 *
 *   - Can this process trace another one?  PRoot (and every ptrace-based
 *     rootless container) is impossible without it.
 *   - Is the process already sandboxed by seccomp?  That constrains which
 *     syscalls a container may emulate.
 *   - Can we create anonymous executable memory (memfd_create) and run a
 *     binary out of it?  Android's W^X policy blocks executing anything from
 *     writable storage, so this is the most likely way a runtime will ever
 *     launch its own binaries.
 *   - Does the kernel still accept 32-bit (aarch32) processes?  Box86, which
 *     translates the 32-bit x86 game code, is itself a 32-bit ARM program.
 *   - How much memory and how many cores are there?  Wine plus a game is not
 *     cheap.
 *
 * Everything here is measured, nothing is assumed. The probe only reads and
 * runs copies of itself; it never touches game files or app data.
 */

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/personality.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef PR_GET_SECCOMP
#define PR_GET_SECCOMP 21
#endif
#ifndef PR_SET_SECCOMP
#define PR_SET_SECCOMP 22
#endif

/* Not every NDK header defines these; the numbers are stable per ABI. */
#ifndef SYS_memfd_create
#if defined(__aarch64__)
#define SYS_memfd_create 279
#elif defined(__arm__)
#define SYS_memfd_create 385
#elif defined(__x86_64__)
#define SYS_memfd_create 319
#elif defined(__i386__)
#define SYS_memfd_create 356
#endif
#endif

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 1u
#endif

#define MARKER_OK "runtime-probe: ok"
#define MARKER_CHILD "runtime-probe-child: ok"

extern char **environ;

/* Read by the traced child so the parent has a valid address to peek at. */
static volatile int g_trace_marker = 0x5eed;

static void emit(const char *key, const char *value) {
    printf("%s: %s\n", key, value);
}

static void emit_int(const char *key, long value) {
    printf("%s: %ld\n", key, value);
}

static void emit_result(const char *key, int ok, const char *detail) {
    if (ok) {
        printf("%s: ALLOWED\n", key);
    } else {
        printf("%s: DENIED (%s)\n", key, detail);
    }
}

/* ------------------------------------------------------------------ lookups */

static void report_property(const char *key, const char *property) {
    char value[PROP_VALUE_MAX] = {0};
    int length = __system_property_get(property, value);
    printf("%s: %s\n", key, length > 0 ? value : "unknown");
}

static void report_memory(void) {
    FILE *file = fopen("/proc/meminfo", "r");
    if (file == NULL) {
        emit("memtotal-mb", "unknown");
        emit("memavailable-mb", "unknown");
        return;
    }

    char line[256];
    long total = -1;
    long available = -1;
    while (fgets(line, sizeof(line), file) != NULL) {
        if (strncmp(line, "MemTotal:", 9) == 0) {
            sscanf(line + 9, "%ld", &total);
        } else if (strncmp(line, "MemAvailable:", 13) == 0) {
            sscanf(line + 13, "%ld", &available);
        }
    }
    fclose(file);

    if (total > 0) emit_int("memtotal-mb", total / 1024);
    if (available > 0) emit_int("memavailable-mb", available / 1024);
}

static void report_cpu_features(void) {
    unsigned long hwcap = getauxval(AT_HWCAP);
    unsigned long hwcap2 = getauxval(AT_HWCAP2);

    printf("cpu-features:");
#if defined(__aarch64__)
    if (hwcap & (1UL << 0)) printf(" fp");
    if (hwcap & (1UL << 1)) printf(" asimd");
    if (hwcap & (1UL << 2)) printf(" evtstrm");
    if (hwcap & (1UL << 3)) printf(" aes");
    if (hwcap & (1UL << 4)) printf(" pmull");
    if (hwcap & (1UL << 5)) printf(" sha1");
    if (hwcap & (1UL << 6)) printf(" sha2");
    if (hwcap & (1UL << 7)) printf(" crc32");
    if (hwcap & (1UL << 8)) printf(" atomics");
    if (hwcap & (1UL << 20)) printf(" asimdrdm");
    if (hwcap2 & (1UL << 1)) printf(" dcpop");
    if (hwcap2 & (1UL << 12)) printf(" bti");
#else
    (void)hwcap2;
    if (hwcap & (1UL << 12)) printf(" neon");
    if (hwcap & (1UL << 16)) printf(" vfpv4");
    if (hwcap & (1UL << 6)) printf(" vfp");
#endif
    printf("\n");
}

static void report_seccomp(void) {
    /* prctl() returns the raw mode: 0 off, 1 strict, 2 filter. */
    int mode = prctl(PR_GET_SECCOMP, 0, 0, 0, 0);
    if (mode >= 0) {
        emit_int("seccomp", mode);
        return;
    }

    /* Fall back to what the kernel says about us. */
    FILE *file = fopen("/proc/self/status", "r");
    char line[256];
    if (file != NULL) {
        while (fgets(line, sizeof(line), file) != NULL) {
            if (strncmp(line, "Seccomp:", 8) == 0) {
                printf("seccomp: %s", line + 8); /* already ends in \n */
                fclose(file);
                return;
            }
        }
        fclose(file);
    }
    emit("seccomp", "unknown");
}

/* ------------------------------------------------------------ capability runs */

/* Can this process trace a child? PRoot's whole model depends on it. */
static void test_ptrace(void) {
    pid_t child = fork();
    if (child < 0) {
        emit_result("ptrace", 0, "fork failed");
        return;
    }

    if (child == 0) {
        if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) == -1) {
            _exit(101);
        }
        raise(SIGSTOP);
        /* Only reached if the parent detached us. */
        _exit(0);
    }

    int status = 0;
    if (waitpid(child, &status, 0) == -1 || !WIFSTOPPED(status)) {
        emit_result("ptrace", 0, "child never stopped");
        kill(child, SIGKILL);
        waitpid(child, NULL, 0);
        return;
    }

    errno = 0;
    long peeked = ptrace(PTRACE_PEEKDATA, child, (void *)&g_trace_marker, NULL);
    int ok = (errno == 0) && (int)peeked == g_trace_marker;

    ptrace(PTRACE_DETACH, child, NULL, 0);
    waitpid(child, &status, 0);
    (void)peeked;

    emit_result("ptrace", ok, ok ? "" : strerror(errno));
}

/*
 * Can we run a binary that never touches the filesystem?
 *
 * Android 10+ refuses to execute anything out of writable app storage, so the
 * runtime will need another way to start Wine and Box86. memfd_create + exec
 * through /proc/self/fd is the usual candidate: it is worth knowing now
 * whether this kernel allows it.
 */
static void test_exec_from_memfd(const char *self_path) {
    /* No MFD_CLOEXEC: the fd must survive long enough for the child to be
       replaced by execve. */
    int fd = (int)syscall(SYS_memfd_create, "bloodlines-probe", 0);
    if (fd < 0) {
        emit_result("memfd-create", 0, strerror(errno));
        emit("exec-memfd", "not tested (no memfd)");
        return;
    }
    emit_result("memfd-create", 1, "");

    FILE *source = fopen(self_path, "rb");
    FILE *sink = fdopen(fd, "wb");
    if (source == NULL || sink == NULL) {
        emit("exec-memfd", "not tested (could not copy the binary)");
        if (source != NULL) fclose(source);
        if (sink != NULL) fclose(sink);
        return;
    }

    char buffer[8192];
    size_t read_bytes;
    while ((read_bytes = fread(buffer, 1, sizeof(buffer), source)) > 0) {
        fwrite(buffer, 1, read_bytes, sink);
    }
    fflush(sink);
    fclose(source);

    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fileno(sink));
    fflush(sink);

    pid_t child = fork();
    if (child == 0) {
        char *const argv[] = {(char *)path, (char *)"--exec-memfd-child", NULL};
        execve(path, argv, environ);
        /* Only reached when the kernel refused. */
        fprintf(stdout, "exec-memfd: DENIED (%s)\n", strerror(errno));
        fflush(stdout);
        _exit(0);
    }

    int status = 0;
    waitpid(child, &status, 0);
    fclose(sink);

    /* The child prints the verdict itself: either the marker from the exec'd
       copy, or the errno from a refused execve. */
    fflush(stdout);
}

/*
 * Does the kernel accept a 32-bit process?
 *
 * Box86, which would translate the game's 32-bit x86 code, is itself a 32-bit
 * ARM program. A growing number of Android 14 devices run 64-bit-only kernels.
 * personality() is only a hint, so the Android properties are reported too:
 * ro.zygote tells us whether the system itself can start 32-bit processes.
 */
static void test_32bit_personality(void) {
    pid_t child = fork();
    if (child < 0) {
        emit("personality32", "not tested (fork failed)");
        return;
    }

    if (child == 0) {
        errno = 0;
        long previous = personality(PER_LINUX32);
        if (previous == -1) {
            fprintf(stdout, "personality32: DENIED (%s)\n", strerror(errno));
        } else {
            /* Restore, and confirm we are back to the 64-bit personality. */
            personality(PER_LINUX);
            fprintf(stdout, "personality32: ALLOWED\n");
        }
        fflush(stdout);
        _exit(0);
    }

    int status = 0;
    waitpid(child, &status, 0);
}

static void report_32bit_indicators(void) {
    report_property("ro-zygote", "ro.zygote");
    report_property("abilist", "ro.product.cpu.abilist");
    report_property("abilist32", "ro.product.cpu.abilist32");
    emit("linker32", access("/system/bin/linker", X_OK) == 0 ? "present" : "absent");
    emit("linker64", access("/system/bin/linker64", X_OK) == 0 ? "present" : "absent");
}

/* ---------------------------------------------------------------- entry point */

int main(int argc, char **argv, char **envp) {
    (void)argc;
    (void)envp;

    if (argc > 1 && strcmp(argv[1], "--exec-memfd-child") == 0) {
        printf("%s\n", MARKER_CHILD);
        return 0;
    }

    printf("%s\n", MARKER_OK);

    /* Who we are. */
    emit("abi",
#if defined(__aarch64__)
         "arm64-v8a"
#elif defined(__arm__)
         "armeabi-v7a"
#elif defined(__x86_64__)
         "x86_64"
#elif defined(__i386__)
         "x86"
#else
         "unknown"
#endif
    );

    char sdk[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.build.version.sdk", sdk) > 0) {
        printf("api-level: %s\n", sdk);
    }
    report_property("device", "ro.product.device");
    report_property("soc", "ro.board.platform");

    /* The machine. */
    long cores = sysconf(_SC_NPROCESSORS_CONF);
    emit_int("cores", cores);
    report_memory();
    report_cpu_features();

    /* What a container needs. */
    test_ptrace();
    report_seccomp();

    char self[4096] = {0};
    ssize_t self_length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (self_length > 0) {
        test_exec_from_memfd(self);
    } else {
        emit("exec-memfd", "not tested (unknown own path)");
    }

    test_32bit_personality();
    report_32bit_indicators();

    printf("%s\n", "runtime-probe: done");
    return 0;
}
