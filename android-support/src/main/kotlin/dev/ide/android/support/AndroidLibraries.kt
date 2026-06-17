package dev.ide.android.support

import dev.ide.android.support.tools.AarExtractor
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** A module's resolved library dependencies, split into the forms the Android pipeline consumes. */
class ResolvedLibraries(
    val compileJars: List<Path>,   // on the compileJava classpath (JARs; AAR `classes.jar` + `libs/*.jar`)
    val dexJars: List<Path>,       // dexed into the APK (the runtime/packaged subset; excludes compileOnly)
    val resDirs: List<Path>,       // AAR `res/` merged by aapt2
    val assetsDirs: List<Path>,    // AAR `assets/` packaged under `assets/`
    val jniLibDirs: List<Path>,    // AAR `jni/<abi>/` packaged under `lib/`
    val aarPackages: List<String>, // AAR manifest packages → aapt2 `--extra-packages` (their `R` + custom attrs)
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
        val aarPackages = ArrayList<String>()

        val cache = HashMap<Path, AarExtractor.Exploded>()
        fun explode(aar: Path) = cache.getOrPut(aar) { AarExtractor.explode(aar, explodeRoot.resolve(dirNameOf(aar))) }

        fun addAarParts(classesJars: List<Path>, res: Path?, assets: Path?, jni: Path?, manifest: Path?) {
            compileJars.addAll(classesJars)
            res?.let { resDirs.add(it) }
            assets?.let { assetsDirs.add(it) }
            jni?.let { jniLibDirs.add(it) }
            manifest?.let { manifestPackage(it)?.let(aarPackages::add) }
        }

        for (root in compileRoots) when {
            isAar(root) -> explode(root).let { addAarParts(it.classesJars, it.resDir, it.assetsDir, it.jniDir, it.manifest) }
            // A Maven-resolved AAR is stored as its exploded `classes.jar`; its res/assets/jni/manifest are siblings.
            isExplodedAar(root) -> root.parent.let { dir ->
                addAarParts(listOf(root), dirOrNull(dir, "res"), dirOrNull(dir, "assets"), dirOrNull(dir, "jni"),
                    dir.resolve("AndroidManifest.xml").takeIf { Files.isRegularFile(it) })
            }
            isJar(root) -> compileJars.add(root)
        }
        for (root in runtimeRoots) when {
            isAar(root) -> dexJars.addAll(explode(root).classesJars)
            isExplodedAar(root) -> dexJars.add(root)
            isJar(root) -> dexJars.add(root)
        }
        return ResolvedLibraries(
            compileJars.distinct(), dexJars.distinct(), resDirs.distinct(), assetsDirs.distinct(),
            jniLibDirs.distinct(), aarPackages.distinct(),
        )
    }

    private fun libraryRoots(module: Module, scope: DependencyScope): List<Path> =
        module.classpath(scope).entries
            .filter { it.kind == ClasspathEntryKind.LIBRARY }
            .map { Paths.get(it.root.path) }

    private fun isAar(p: Path) = p.toString().endsWith(".aar", ignoreCase = true)
    private fun isJar(p: Path) = p.toString().endsWith(".jar", ignoreCase = true)
    private fun dirNameOf(aar: Path): String = aar.fileName.toString().substringBeforeLast('.')

    /**
     * A `classes.jar` that the dependency resolver already exploded out of an AAR — recognised by the
     * `res/`/`assets/`/manifest siblings (or the resolver's `.extracted` marker) sitting next to it. The
     * model stores AARs by this exploded jar (not the `.aar`), so this is the common Maven-dependency form.
     */
    private fun isExplodedAar(p: Path): Boolean = p.fileName?.toString() == "classes.jar" && p.parent?.let { dir ->
        Files.isRegularFile(dir.resolve(".extracted")) || Files.isDirectory(dir.resolve("res")) ||
            Files.isRegularFile(dir.resolve("AndroidManifest.xml"))
    } == true

    private fun dirOrNull(parent: Path, name: String): Path? = parent.resolve(name).takeIf { Files.isDirectory(it) }

    /** The `package` attribute of an AAR's bundled `AndroidManifest.xml`, fed to aapt2 `--extra-packages`. */
    private fun manifestPackage(manifest: Path): String? = runCatching {
        val text = Files.readAllBytes(manifest).toString(Charsets.UTF_8)
        Regex("""<manifest\b[^>]*\bpackage\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
    }.getOrNull()
}
