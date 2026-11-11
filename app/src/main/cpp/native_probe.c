/*
 * Native execution probe.
 *
 * A tiny C program shipped inside the APK as libnative_probe.so and executed by
 * the launcher. It reports how this device behaves for the things the future
 * Wine/Box86 runtime will depend on:
 *
 *   1. Can a native binary bundled as a shared library actually run?
 *   2. Which ABI and API level are we on?
 *   3. Is executing a file from writable app storage allowed, or does the
 *      Android 10+ W^X policy block it?
 *
 * Nothing here is game code; it is a one-off capability check.
 */

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

#define PROBE_MARKER "native-probe: ok"

#if defined(__aarch64__)
#define PROBE_ABI "arm64-v8a"
#elif defined(__arm__)
#define PROBE_ABI "armeabi-v7a"
#elif defined(__i386__)
#define PROBE_ABI "x86"
#elif defined(__x86_64__)
#define PROBE_ABI "x86_64"
#else
#define PROBE_ABI "unknown"
#endif

static int copy_file(const char *from, const char *to) {
    FILE *in = fopen(from, "rb");
    if (in == NULL) {
        return 0;
    }
    FILE *out = fopen(to, "wb");
    if (out == NULL) {
        fclose(in);
        return 0;
    }

    char buffer[8192];
    size_t read_bytes;
    int ok = 1;
    while ((read_bytes = fread(buffer, 1, sizeof(buffer), in)) > 0) {
        if (fwrite(buffer, 1, read_bytes, out) != read_bytes) {
            ok = 0;
            break;
        }
    }

    fclose(in);
    if (fclose(out) != 0) {
        ok = 0;
    }
    return ok;
}

/*
 * Copies this executable into a writable app directory and tries to run the
 * copy. On Android 10+ the kernel refuses (W^X), which is the reason every
 * runtime binary has to ship as a shared library instead.
 */
static void probe_exec_from_app_data(const char *directory) {
    char self[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length <= 0) {
        printf("  exec-from-app-data: unknown (cannot resolve /proc/self/exe)\n");
        return;
    }
    self[length] = '\0';

    char copy[PATH_MAX];
    snprintf(copy, sizeof(copy), "%s/probe_exec_test", directory);
    if (!copy_file(self, copy)) {
        printf("  exec-from-app-data: unknown (could not copy the binary)\n");
        return;
    }
    chmod(copy, 0700);

    pid_t child = fork();
    if (child < 0) {
        printf("  exec-from-app-data: unknown (fork failed)\n");
        unlink(copy);
        return;
    }
    if (child == 0) {
        execl(copy, copy, "--child", (char *)NULL);
        _exit(127);
    }

    int status = 0;
    waitpid(child, &status, 0);
    unlink(copy);

    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        printf("  exec-from-app-data: ALLOWED (files in app storage can be executed here)\n");
    } else {
        printf("  exec-from-app-data: DENIED (Android 10+ W^X: runtime binaries must ship as lib*.so)\n");
    }
}

int main(int argc, char **argv) {
    /* Invoked by the copy above, to prove the exec really happened. */
    if (argc > 1 && strcmp(argv[1], "--child") == 0) {
        printf("child: executed from writable app storage\n");
        return 0;
    }

    printf("%s\n", PROBE_MARKER);
    printf("  path:        %s\n", argv[0]);
    printf("  abi:         %s\n", PROBE_ABI);

    char sdk[PROP_VALUE_MAX] = "";
    __system_property_get("ro.build.version.sdk", sdk);
    printf("  api-level:   %s\n", sdk[0] != '\0' ? sdk : "unknown");

    struct utsname info;
    if (uname(&info) == 0) {
        printf("  kernel:      %s %s\n", info.sysname, info.machine);
    }

    printf("  page-size:   %ld\n", sysconf(_SC_PAGESIZE));

    if (argc > 1 && argv[1] != NULL) {
        probe_exec_from_app_data(argv[1]);
    } else {
        printf("  exec-from-app-data: skipped (no writable directory given)\n");
    }

    return 0;
}
