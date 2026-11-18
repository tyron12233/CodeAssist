// dotnetlaunch: starts a .NET program on an Android device, for the C# plugin.
//
//   libdotnetlaunch.so <app.dll> [args...]
//
// Does what the `dotnet` executable does for `dotnet app.dll`: finds the newest hostfxr under
// $DOTNET_ROOT/host/fxr, loads it, and hands it the command line. hostfxr then resolves the shared
// framework under $DOTNET_ROOT/shared and runs the program. Exits with the program's exit code, or 127
// when the runtime could not be started.
//
// Why not the `dotnet` executable itself: an app may only execute a file from its installed package's
// native library directory, where every file is named lib*.so, and `dotnet` refuses to run under another
// name. hostfxr has no such check, and it is a library, which an app may load from its own storage.
//
// DOTNET_ROOT must be set. The tree it points at is only read. The caller also sets
// DOTNET_OPENSSL_VERSION_OVERRIDE=3 and puts <root>/lib on LD_LIBRARY_PATH, so the runtime loads the
// OpenSSL carried in the tree rather than the device's own libssl.so, which is a different library.

#include <android/api-level.h>
#include <dirent.h>
#include <dlfcn.h>
#include <limits.h>
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef int (*hostfxr_main_startupinfo_fn)(
    int argc, const char **argv, const char *host_path, const char *dotnet_root, const char *app_path);

// Compares two dotted version strings numerically, field by field, so 10.0.9 sorts below 10.0.13.
// A field that is not a number ends the comparison, with what remains compared as text.
static int compare_versions(const char *a, const char *b) {
    while (*a && *b) {
        char *end_a, *end_b;
        long field_a = strtol(a, &end_a, 10);
        long field_b = strtol(b, &end_b, 10);
        if (end_a == a || end_b == b) break;
        if (field_a != field_b) return field_a < field_b ? -1 : 1;
        a = end_a;
        b = end_b;
        if (*a == '.' && *b == '.') {
            a++;
            b++;
        } else {
            break;
        }
    }
    return strcmp(a, b);
}

// Writes the path of the newest libhostfxr.so under <root>/host/fxr into `out`. Returns 0 if there is one.
static int find_hostfxr(const char *root, char *out, size_t size) {
    char dir[PATH_MAX];
    snprintf(dir, sizeof dir, "%s/host/fxr", root);

    DIR *versions = opendir(dir);
    if (!versions) return -1;

    char best[NAME_MAX + 1] = "";
    struct dirent *entry;
    while ((entry = readdir(versions)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        if (best[0] == '\0' || compare_versions(entry->d_name, best) > 0) {
            snprintf(best, sizeof best, "%s", entry->d_name);
        }
    }
    closedir(versions);

    if (best[0] == '\0') return -1;
    snprintf(out, size, "%s/%s/libhostfxr.so", dir, best);
    return 0;
}

// Android 11 and later tag heap pointers in their top byte on arm64. The runtime keeps its own bits in
// pointers and crashes on a tagged one, so tagging is turned off before anything is allocated. An app does
// the same with allowNativeHeapPointerTagging, which does not reach a process started with exec.
//
// mallopt takes the request from Android 12. Android 11 has only android_mallopt, which libc exports for
// the platform's own use; it is looked up by name so that the launcher still loads where it is absent.
static void disable_heap_tagging(void) {
#if defined(__aarch64__)
    if (mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE) != 0) return;

    // The request number below is Android 11's; other releases number their requests differently.
    if (android_get_device_api_level() != 30) return;

    typedef int (*android_mallopt_fn)(int opcode, void *arg, size_t arg_size);
    android_mallopt_fn android_mallopt = (android_mallopt_fn) dlsym(RTLD_DEFAULT, "android_mallopt");
    if (android_mallopt) {
        const int set_heap_tagging_level = 8;
        int none = 0;
        android_mallopt(set_heap_tagging_level, &none, sizeof none);
    }
#endif
}

int main(int argc, char **argv) {
    disable_heap_tagging();

    const char *root = getenv("DOTNET_ROOT");
    if (!root || !*root) {
        fprintf(stderr, "dotnetlaunch: DOTNET_ROOT is not set\n");
        return 127;
    }

    char hostfxr_path[PATH_MAX];
    if (find_hostfxr(root, hostfxr_path, sizeof hostfxr_path) != 0) {
        fprintf(stderr, "dotnetlaunch: no hostfxr under %s/host/fxr\n", root);
        return 127;
    }

    void *hostfxr = dlopen(hostfxr_path, RTLD_NOW | RTLD_GLOBAL);
    if (!hostfxr) {
        fprintf(stderr, "dotnetlaunch: %s\n", dlerror());
        return 127;
    }

    hostfxr_main_startupinfo_fn run =
        (hostfxr_main_startupinfo_fn) dlsym(hostfxr, "hostfxr_main_startupinfo");
    if (!run) {
        fprintf(stderr, "dotnetlaunch: %s has no hostfxr_main_startupinfo\n", hostfxr_path);
        return 127;
    }

    // The paths `dotnet` would report for itself. Neither file has to exist: with no <root>/dotnet.dll
    // beside the host, hostfxr treats the first argument as the program to run.
    char host_path[PATH_MAX];
    char app_path[PATH_MAX];
    snprintf(host_path, sizeof host_path, "%s/dotnet", root);
    snprintf(app_path, sizeof app_path, "%s/dotnet.dll", root);

    const char **args = calloc((size_t) argc + 1, sizeof *args);
    if (!args) return 127;
    args[0] = host_path;
    for (int i = 1; i < argc; i++) args[i] = argv[i];

    return run(argc, args, host_path, root, app_path);
}
