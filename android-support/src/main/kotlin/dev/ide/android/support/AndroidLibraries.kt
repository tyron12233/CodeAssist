package dev.ide.android.support

import dev.ide.android.support.tools.AarExtractor
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import java.nio.file.Path
import java.nio.file.Paths

/** A module's resolved library dependencies, split into the forms the Android pipeline consumes. */
class ResolvedLibraries(
    val compileJars: List<Path>,   // on the compileJava classpath (JARs; AAR `classes.jar` + `libs/*.jar`)
    val dexJars: List<Path>,       // dexed into the APK (the runtime/packaged subset; excludes compileOnly)
    val resDirs: List<Path>,       // AAR `res/` merged by aapt2
    val assetsDirs: List<Path>,    // AAR `assets/` packaged under `assets/`
    val jniLibDirs: List<Path>,    // AAR `jni/<abi>/` packaged under `lib/`
)

/**
 * Makes the Android build library-aware. A module's library dependencies arrive
 * through the model's classpath as `LIBRARY` entries pointing at either a `.jar` or a `.aar`. This
 * classifies them and explodes AARs ([AarExtractor]) so the build can route each part correctly: JAR/AAR
 * code to compile + dex, AAR resources to aapt2, AAR assets/jni to packaging.
 *
 * Compile vs dex scope is honoured: the compile classpath uses the compile-visible set (`api` +
 * `implementation` + `compileOnly`); only the runtime set is dexed/packaged, so a `compileOnly` library
 * (the `provided` semantics — e.g. an annotation API) is on the classpath but never lands in the APK.
 */
object AndroidLibraries {

    fun resolve(module: Module, explodeRoot: Path): ResolvedLibraries {
        val compileRoots = libraryRoots(module, DependencyScope.IMPLEMENTATION)
        val runtimeRoots = libraryRoots(module, DependencyScope.RUNTIME_ONLY)

        val compileJars = ArrayList<Path>()
        val dexJars = ArrayList<Path>()
        val resDirs = ArrayList<Path>()
        val assetsDirs = ArrayList<Path>()
        val jniLibDirs = ArrayList<Path>()

        val cache = HashMap<Path, AarExtractor.Exploded>()
        fun explode(aar: Path) = cache.getOrPut(aar) { AarExtractor.explode(aar, explodeRoot.resolve(dirNameOf(aar))) }

        for (root in compileRoots) when {
            isAar(root) -> {
                val e = explode(root)
                compileJars.addAll(e.classesJars)
                e.resDir?.let { resDirs.add(it) }
                e.assetsDir?.let { assetsDirs.add(it) }
                e.jniDir?.let { jniLibDirs.add(it) }
            }
            isJar(root) -> compileJars.add(root)
        }
        for (root in runtimeRoots) when {
            isAar(root) -> dexJars.addAll(explode(root).classesJars)
            isJar(root) -> dexJars.add(root)
        }
        return ResolvedLibraries(
            compileJars.distinct(), dexJars.distinct(), resDirs.distinct(), assetsDirs.distinct(), jniLibDirs.distinct(),
        )
    }

    private fun libraryRoots(module: Module, scope: DependencyScope): List<Path> =
        module.classpath(scope).entries
            .filter { it.kind == ClasspathEntryKind.LIBRARY }
            .map { Paths.get(it.root.path) }

    private fun isAar(p: Path) = p.toString().endsWith(".aar", ignoreCase = true)
    private fun isJar(p: Path) = p.toString().endsWith(".jar", ignoreCase = true)
    private fun dirNameOf(aar: Path): String = aar.fileName.toString().substringBeforeLast('.')
}
