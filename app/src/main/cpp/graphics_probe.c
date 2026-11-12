/*
 * Graphics capability probe.
 *
 * Answers one question the launcher cannot answer from Java: which route to the
 * GPU actually exists on this device?
 *
 * That matters because the two candidate backends need different things. Mesa's
 * Panfrost wants a DRM render node (/dev/dri/renderD*). The community PanVK
 * builds for Mali-G52 go through Arm's proprietary kbase node (/dev/mali0)
 * instead, because those devices ship the closed driver and no DRM node. Which
 * one is present decides which graphics backends are even worth offering, and
 * there is no Java API for it: opening a device node requires native code and,
 * on the DRM path, ioctls.
 *
 * Findings are printed as `key: value` lines for RuntimeProbe.facts() to read.
 * Nothing here is fatal: a device that refuses every node is a finding, not a
 * crash, and the launcher falls back to llvmpipe when both routes are absent.
 */

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* DRM ioctls, so the probe does not have to depend on libdrm being present. */
#define DRM_IOCTL_BASE 'd'
#define DRM_COMMAND_BASE 0x40

struct drm_version {
    int version_major;
    int version_minor;
    int version_patchlevel;
    unsigned long name_len;
    char *name;
    unsigned long date_len;
    char *date;
    unsigned long desc_len;
    char *desc;
};

#define DRM_IOCTL_VERSION \
    _IOWR(DRM_IOCTL_BASE, 0x00, struct drm_version)

static void emit(const char *key, const char *value) {
    printf("%s: %s\n", key, value);
}

static void emit_int(const char *key, int value) {
    printf("%s: %d\n", key, value);
}

/* True when the path can be opened at all. */
static int can_open(const char *path) {
    int fd = open(path, O_RDWR);
    if (fd < 0) fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    close(fd);
    return 1;
}

/*
 * DRM render nodes. These are what Mesa's open drivers (Panfrost, and therefore
 * llvmpipe-over-DRM too) need. Most phones with Arm's closed driver do not have
 * one, which is exactly why this has to be measured rather than assumed.
 */
static void probe_drm(void) {
    DIR *dir = opendir("/dev/dri");
    if (!dir) {
        emit("dri", "absent");
        return;
    }

    char found[512];
    found[0] = '\0';
    int count = 0;

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "renderD", 7) != 0) continue;

        char path[256];
        snprintf(path, sizeof(path), "/dev/dri/%s", entry->d_name);

        int fd = open(path, O_RDWR);
        if (fd < 0) continue;

        struct drm_version version;
        char name[128] = {0};
        char date[128] = {0};
        char desc[256] = {0};
        memset(&version, 0, sizeof(version));
        version.name_len = sizeof(name) - 1;
        version.name = name;
        version.date_len = sizeof(date) - 1;
        version.date = date;
        version.desc_len = sizeof(desc) - 1;
        version.desc = desc;

        int ok = (ioctl(fd, DRM_IOCTL_VERSION, &version) == 0);
        close(fd);

        if (count > 0) strncat(found, " ", sizeof(found) - strlen(found) - 1);
        strncat(found, entry->d_name, sizeof(found) - strlen(found) - 1);
        count++;

        if (ok) {
            printf("dri-node: %s\n", entry->d_name);
            printf("dri-driver: %s\n", name[0] ? name : "unknown");
            printf("dri-version: %d.%d.%d\n",
                   version.version_major, version.version_minor, version.version_patchlevel);
        } else {
            printf("dri-node: %s (ioctl refused: %s)\n", entry->d_name, strerror(errno));
        }
    }
    closedir(dir);

    if (count == 0) {
        emit("dri", "no render nodes");
    } else {
        emit("dri", found);
    }
}

/*
 * Arm's kbase node. Present on phones shipping the proprietary Mali driver,
 * which is this device's situation. Opening it proves the node exists and is
 * reachable; it does not prove a Vulkan driver is installed on top of it, which
 * is why the result is reported as "node present" and never as "Vulkan works".
 */
static void probe_mali_kbase(void) {
    static const char *const paths[] = {
        "/dev/mali0",
        "/dev/mali",
        "/dev/mali_kbase",
    };

    for (unsigned int i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        int fd = open(paths[i], O_RDWR);
        if (fd < 0) fd = open(paths[i], O_RDONLY);
        if (fd < 0) continue;

        close(fd);
        printf("mali-node: %s\n", paths[i]);
        emit("mali-kbase", "reachable");
        return;
    }

    emit("mali-kbase", "not found");
}

/*
 * The Vulkan loader. If a loader and an ICD are installed, the loader exposes
 * the Vulkan entry point; resolving it proves a Vulkan stack is loadable. It
 * says nothing about which GPU it would pick, so this is a precondition, not a
 * result: a device can pass this and still have no working Vulkan driver.
 */
static void probe_vulkan_loader(void) {
    void *handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        emit("vulkan-loader", "not loadable");
        return;
    }

    void *symbol = dlsym(handle, "vkGetInstanceProcAddr");
    if (!symbol) {
        emit("vulkan-loader", "present but vkGetInstanceProcAddr is missing");
        dlclose(handle);
        return;
    }

    emit("vulkan-loader", "loadable");
    dlclose(handle);
}

/*
 * GLES, through EGL. Java can already read GL strings once it has a context, so
 * this only checks which of the two libraries is loadable and whether EGL's
 * entry point resolves. It is a corroboration of GpuInfo rather than a
 * replacement for it.
 */
static void probe_egl(void) {
    void *egl = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    if (egl && dlsym(egl, "eglGetDisplay")) {
        emit("egl", "loadable");
    } else {
        emit("egl", "not loadable");
        if (egl) dlclose(egl);
    }

    void *gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    if (gles && dlsym(gles, "glGetString")) {
        emit("gles", "loadable");
    } else {
        emit("gles", "not loadable");
        if (gles) dlclose(gles);
    }
    if (gles) dlclose(gles);
    if (egl) dlclose(egl);
}

int main(void) {
    printf("graphics-probe: ok\n");
    emit_int("pid", (int) getpid());

    probe_drm();
    probe_mali_kbase();
    probe_vulkan_loader();
    probe_egl();

    printf("graphics-probe: done\n");
    fflush(stdout);
    return 0;
}
