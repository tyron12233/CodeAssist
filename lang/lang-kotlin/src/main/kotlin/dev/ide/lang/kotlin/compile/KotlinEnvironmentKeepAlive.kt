package dev.ide.lang.kotlin.compile

/**
 * Keeps the Kotlin compiler's application-level environment alive across compiles.
 *
 * Every `K2JVMCompiler.exec(...)` builds a `KotlinCoreEnvironment` over a per-invocation disposable and
 * disposes it when the compile ends. The IntelliJ-core *application* environment underneath it (file types,
 * parser definitions, and — crucially — the jar `VirtualFileSystem` that holds each opened/mmapped classpath
 * jar) is reference-counted by those disposables: when the count hits zero it is torn down, so the next
 * compile re-stands-up the whole environment and re-reads `android.jar`, the stdlib, and every library jar.
 *
 * The compiler exposes a knob for exactly this: `kotlin.environment.keepalive` (read once, when the
 * application environment is first created, and baked into its dispose handler). With it set, the application
 * environment survives a refcount of zero, so the jar FS — and the jars it has already read, via the fast
 * mmap-backed FS once [dev.ide.build.kotlinc.FastJarCleanerArtPass] enables it on ART — stays warm for every
 * later compile. The first build pays the jar-read cost; the rest reuse it.
 *
 * The property must be set **before** the environment is first created, so both the build compiler
 * ([KotlinJvmCompiler]) and the editor parse host call [ensure] before they touch any compiler environment.
 * The reused application environment is application-, not project-scoped, and classpath jars are immutable by
 * path (content-addressed dep cache, immutable `android.jar`/stdlib), so cross-compile reuse is safe.
 */
object KotlinEnvironmentKeepAlive {

    private const val PROPERTY = "kotlin.environment.keepalive"

    @Volatile
    private var done = false

    /** Set the keepalive property once, unless the host has already chosen a value. Idempotent and cheap. */
    fun ensure() {
        if (done) return
        synchronized(this) {
            if (done) return
            if (System.getProperty(PROPERTY) == null) System.setProperty(PROPERTY, "true")
            done = true
        }
    }
}
