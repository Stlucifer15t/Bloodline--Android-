/*
 * Process management probe.
 *
 * Measures the things that decide whether a Wine process tree can survive on
 * this device. Three questions, each of them a real failure mode rather than a
 * formality:
 *
 * 1. Can we fork, exec something, and get its exit status back? Trivial on a
 *    normal Linux box; not guaranteed under Android's process accounting, and
 *    if it does not work there is no way to detect the game closing.
 * 2. Can we fork a small tree and reap all of it? Wine does not run one process,
 *    it runs a server plus services plus the game, so the question is whether a
 *    tree survives, not whether a single child does.
 * 3. How many processes are we allowed? Android 12 and later kill an app's
 *    children past a fixed cap, and the victim disappears with no exit status at
 *    all. Knowing the number tells the runtime whether to budget processes and
 *    whether a vanished game is a crash or an Android policy kill.
 *
 * The limit is read, not discovered by hitting it: deliberately spawning
 * processes until Android kills them would be a hostile thing to do to a phone.
 */

#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>

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

/* Reads /proc/self/limits, which is where Android's cap is published. */
static void report_limits(void) {
    FILE *file = fopen("/proc/self/limits", "re");
    if (!file) {
        emit("limits", "unreadable");
        return;
    }

    char line[512];
    while (fgets(line, sizeof(line), file) != NULL) {
        if (strncmp(line, "Max processes", 13) != 0) continue;

        long soft = -1;
        long hard = -1;
        /* "Max processes   n   m  ..." — the soft and hard values follow the label. */
        const char *cursor = line + 13;
        soft = strtol(cursor, (char **) &cursor, 10);
        hard = strtol(cursor, (char **) &cursor, 10);

        if (soft > 0) emit_int("max-processes-soft", soft);
        if (hard > 0) emit_int("max-processes-hard", hard);
        break;
    }
    fclose(file);
}

/*
 * fork -> exec -> waitpid, end to end.
 *
 * Uses /system/bin/true rather than something we ship: this is about whether
 * the kernel lets us run a child and collect its status, not about our own
 * binaries. exit code 0 with a clean exit proves the whole round trip.
 */
static void test_exec_wait(void) {
    static const char *const candidates[] = {
        "/system/bin/true",
        "/system/bin/false",
        "/bin/true",
    };

    const char *program = NULL;
    for (unsigned int i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        if (access(candidates[i], X_OK) == 0) {
            program = candidates[i];
            break;
        }
    }

    if (!program) {
        emit_result("exec-wait", 0, "no test binary is executable");
        return;
    }

    pid_t pid = fork();
    if (pid < 0) {
        emit_result("exec-wait", 0, strerror(errno));
        return;
    }

    if (pid == 0) {
        execl(program, program, (char *) NULL);
        _exit(127);
    }

    int status = 0;
    pid_t reaped = waitpid(pid, &status, 0);
    if (reaped != pid) {
        emit_result("exec-wait", 0, strerror(errno));
        return;
    }

    if (WIFEXITED(status)) {
        emit_result("exec-wait", 1, "child exited cleanly");
        emit_int("exec-wait-status", WEXITSTATUS(status));
    } else if (WIFSIGNALED(status)) {
        emit_result("exec-wait", 0, "child was killed by a signal");
        emit_int("exec-wait-signal", WTERMSIG(status));
    } else {
        emit_result("exec-wait", 0, "child neither exited nor was signalled");
    }
}

/*
 * A small tree: four children at once, all reaped.
 *
 * Four is not the number Wine needs, it is the number that proves parallel
 * children are accounted for correctly without being rude to the device.
 */
static void test_process_tree(void) {
    const int children = 4;
    pid_t pids[4];
    int started = 0;

    for (int i = 0; i < children; i++) {
        pid_t pid = fork();
        if (pid < 0) break;
        if (pid == 0) {
            /* Children sleep briefly so the parent reaps real processes. */
            usleep(20 * 1000);
            _exit(0);
        }
        pids[started++] = pid;
    }

    if (started == 0) {
        emit_result("process-tree", 0, strerror(errno));
        return;
    }

    int reaped = 0;
    int lost = 0;
    for (int i = 0; i < started; i++) {
        int status = 0;
        pid_t result = waitpid(pids[i], &status, 0);
        if (result == pids[i] && WIFEXITED(status) && WEXITSTATUS(status) == 0) {
            reaped++;
        } else {
            lost++;
        }
    }

    emit_int("process-tree-started", started);
    emit_int("process-tree-reaped", reaped);
    emit_int("process-tree-lost", lost);
    emit_result(
        "process-tree",
        lost == 0,
        lost == 0 ? "every child was reaped" : "some children vanished before reaping"
    );
}

/*
 * How many processes this app is currently running. Not a limit, but a
 * baseline: Wine adds many, so knowing where we start says how much headroom
 * there is before Android's cap becomes a problem.
 */
static void report_current_processes(void) {
    DIR *dir = opendir("/proc");
    if (!dir) {
        emit("current-threads", "unreadable");
        return;
    }

    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        count++;
    }
    closedir(dir);
    emit_int("proc-entries", count);
}

int main(void) {
    printf("process-probe: ok\n");
    emit_int("pid", (int) getpid());

    report_limits();
    test_exec_wait();
    test_process_tree();
    report_current_processes();

    printf("process-probe: done\n");
    fflush(stdout);
    return 0;
}
