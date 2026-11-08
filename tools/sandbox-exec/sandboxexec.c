// sandboxexec: runs a program a plugin packages, from the plugin sandbox, through the system linker.
//
// A plugin runs its toolchain as `<nativeLibraryDir>/lib<name>.so`. Installed, that directory is the one the
// package installer extracted, and Android lets an app exec from it. The sandbox loads a plugin build that was
// never installed, so its libraries are unpacked into the app's own storage, where Android 10+ refuses exec
// (error=13). The system linker may still load a file from there (the rule that lets an app dlopen its own
// files), which is how the NDK plugin's Run starts a user's program: `/system/bin/linker64 <file>`.
//
// The plugin's own code cannot be changed to do that, so the sandbox puts this program behind the path the
// plugin runs: `<dir>/lib<name>.so` is a symlink to the IDE's installed copy of this file (which may be exec'd),
// and the real program sits at `<dir>/.programs/lib<name>.so`. Started through the symlink, this reads the path
// it was started as (AT_EXECFN), and replaces itself with `<linker> <dir>/.programs/lib<name>.so <args...>`,
// keeping the environment. The linker is linker64 or linker by the program's ELF class.
//
// Only a position-independent program (what the NDK builds) can be loaded by the linker; a static program at
// a fixed address cannot, and that is said instead of run.
//
// Freestanding: no libc, raw system calls, statically linked, so it needs nothing from the device and builds
// without the NDK (see build.sh). Shipped in the IDE as jniLibs/<abi>/libsandboxexec.so.

#define AT_FDCWD (-100)
#define O_RDONLY 0
#define O_CLOEXEC 02000000
#define AT_EXECFN 31
#define PATH_MAX 4096
#define EXIT_CANNOT_RUN 127

#if defined(__aarch64__)
#define SYS_openat 56
#define SYS_close 57
#define SYS_read 63
#define SYS_write 64
#define SYS_exit_group 94
#define SYS_execve 221
static long sys3(long n, long a, long b, long c) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory", "cc");
    return x0;
}
__asm__(".text\n.global _start\n.type _start,%function\n_start:\n"
        "  mov x29, #0\n  mov x30, #0\n  mov x0, sp\n  bl cstart\n  brk #0\n");
#elif defined(__x86_64__)
#define SYS_read 0
#define SYS_write 1
#define SYS_close 3
#define SYS_execve 59
#define SYS_exit_group 231
#define SYS_openat 257
static long sys3(long n, long a, long b, long c) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c) : "rcx", "r11", "memory", "cc");
    return ret;
}
__asm__(".text\n.global _start\n.type _start,@function\n_start:\n"
        "  xor %rbp, %rbp\n  mov %rsp, %rdi\n  and $-16, %rsp\n  call cstart\n  hlt\n");
#elif defined(__arm__)
#define SYS_read 3
#define SYS_write 4
#define SYS_close 6
#define SYS_execve 11
#define SYS_exit_group 248
#define SYS_openat 322
static long sys3(long n, long a, long b, long c) {
    register long r7 __asm__("r7") = n;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    __asm__ volatile("svc #0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2) : "memory", "cc");
    return r0;
}
__asm__(".text\n.global _start\n.type _start,%function\n_start:\n"
        "  mov fp, #0\n  mov lr, #0\n  mov r0, sp\n  bic sp, sp, #15\n  bl cstart\n  udf #0\n");
#elif defined(__i386__)
#define SYS_read 3
#define SYS_write 4
#define SYS_close 6
#define SYS_execve 11
#define SYS_exit_group 252
#define SYS_openat 295
static long sys3(long n, long a, long b, long c) {
    long ret;
    __asm__ volatile("int $0x80" : "=a"(ret) : "a"(n), "b"(a), "c"(b), "d"(c) : "memory", "cc");
    return ret;
}
__asm__(".text\n.global _start\n.type _start,@function\n_start:\n"
        "  xor %ebp, %ebp\n  mov %esp, %eax\n  and $-16, %esp\n  sub $12, %esp\n  push %eax\n  call cstart\n  hlt\n");
#else
#error "unsupported architecture"
#endif

static unsigned long len(const char *s) {
    unsigned long n = 0;
    while (s[n]) n++;
    return n;
}

static void say(const char *s) { sys3(SYS_write, 2, (long)s, (long)len(s)); }

__attribute__((noreturn)) static void die(const char *program, const char *why) {
    say("sandboxexec: ");
    if (program) { say(program); say(": "); }
    say(why);
    say("\n");
    for (;;) sys3(SYS_exit_group, EXIT_CANNOT_RUN, 0, 0);
}

// Appends [s] to [buf] at [*at]; false when it would not fit (with the terminating NUL).
static int append(char *buf, unsigned long *at, const char *s, unsigned long n) {
    if (*at + n >= PATH_MAX) return 0;
    for (unsigned long i = 0; i < n; i++) buf[(*at)++] = s[i];
    buf[*at] = 0;
    return 1;
}

static char real[PATH_MAX];

__attribute__((used, noreturn)) void cstart(long *sp) {
    long argc = sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;
    char **e = envp;
    while (*e) e++;

    // The path this was started as: the symlink in the plugin's library directory. argv[0] is what the
    // caller chose to pass, which is usually the same; AT_EXECFN is what execve was given.
    const char *self = argc > 0 ? argv[0] : 0;
    for (unsigned long *aux = (unsigned long *)(e + 1); aux[0]; aux += 2) {
        if (aux[0] == AT_EXECFN && aux[1]) { self = (const char *)aux[1]; break; }
    }
    if (!self || !*self) die(0, "started without a program path");

    unsigned long n = len(self), slash = n;
    while (slash > 0 && self[slash - 1] != '/') slash--;
    unsigned long at = 0;
    const char sub[] = ".programs/";
    int fits = slash > 0 ? append(real, &at, self, slash) : append(real, &at, "./", 2);
    fits = fits && append(real, &at, sub, sizeof(sub) - 1) && append(real, &at, self + slash, n - slash);
    if (!fits) die(self, "path too long");

    unsigned char head[20];
    long fd = sys3(SYS_openat, AT_FDCWD, (long)real, O_RDONLY | O_CLOEXEC);
    if (fd < 0) die(real, "not found (run only through the plugin sandbox's library directory)");
    long got = sys3(SYS_read, fd, (long)head, sizeof(head));
    sys3(SYS_close, fd, 0, 0);
    if (got < 18 || head[0] != 0x7f || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') die(real, "not an ELF program");
    if (head[16] != 3 || head[17] != 0) {
        die(real, "a static program, which Android runs only from an installed app: install the plugin to run it");
    }
    const char *linker = head[4] == 2 ? "/system/bin/linker64" : "/system/bin/linker";

    char *args[argc + 2];
    args[0] = (char *)linker;
    args[1] = real;
    for (long i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[argc + 1] = 0;
    sys3(SYS_execve, (long)linker, (long)args, (long)envp);
    die(linker, "could not be started");
}
