package dev.ide.spi

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The published SPI keeps every member a plugin compiled against it can already name.
 *
 * An installed plugin does not carry the SPI: it compiles against the published artifacts and links against
 * the IDE's own copies at runtime, through its classloader's parent. So what a plugin depends on is the
 * BYTECODE of each published module, member by member, descriptor by descriptor. Kotlin will let that break
 * while every source file still compiles:
 *
 *  * a top-level function moved between files in the same package changes facade class
 *    (`ProjectModelKt.module` becoming `JvmProjectModelKt.module` is a source-compatible refactor and a
 *    binary break);
 *  * a constructor replaced by a same-named factory function keeps callers compiling and stops their
 *    compiled form resolving;
 *  * a defaulted parameter added to a public function changes its `$default` bridge's descriptor.
 *
 * None of that fails the build, and none of it fails at load either: the plugin is discovered, consented,
 * loaded, and then throws `NoSuchMethodError` the first time the user reaches the feature. That is the worst
 * failure mode available, which is why it is worth a test that cannot be talked out of.
 *
 * The baseline is one file per published module, `<module>/api/<module>.api`, holding that module's public
 * surface in a flat, diffable form. Only REMOVALS fail: the SPI is additive by design, and a baseline that
 * has to be regenerated for every added member is one nobody reads. Regenerate deliberately, at a release:
 *
 * ```
 * ./gradlew :spi-compat:test -Dspi.updateBaselines=true
 * ```
 *
 * and the diff that produces is the list of what the next SPI version changes for a plugin author.
 *
 * A removal that IS intended is written into `plugins/spi-compat/accepted-breaks.txt`, one line per member,
 * so it is decided once, in review, rather than discovered by whoever installed the plugin. The file is
 * held to the same standard in reverse: a line there whose member is back in the build fails too.
 */
class SpiSurfaceTest {

    @Test
    fun `every published module keeps the surface its baseline pinned`() {
        val root = repoRoot()
        val update = System.getProperty("spi.updateBaselines") == "true"
        val classpath = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map { File(it) }

        val published = publishedModules(root)
        assertTrue(published.isNotEmpty(), "found no published modules under $root; has the layout changed?")

        // What a plugin can name is the union of the published modules, not one of them: they all arrive on
        // the host's classloader, and a class that MOVES between two of them (`ExtensionPoint` went from
        // :platform-core to :model-api, which :platform-core still exposes as an `api` dependency) breaks
        // nothing. Per-module baselines, whole-SPI verdict.
        val whole = mutableMapOf<String, MutableSet<String>>()

        val problems = mutableListOf<String>()
        for ((name, moduleDir) in published) {
            // A published module whose classes are nowhere on this module's test classpath would be
            // silently unchecked, which is exactly the module most likely to be the newly added one.
            val roots = classpath.filter { "/$name/build/" in it.invariantPath() }
            assertTrue(
                roots.isNotEmpty(),
                ":$name applies the publishing convention but nothing of it is on :spi-compat's test " +
                    "classpath. Add `testImplementation(project(\":$name\"))` to plugins/spi-compat.",
            )

            val surface = readSurface(roots)
            for ((owner, entries) in surface) whole.getOrPut(owner) { mutableSetOf() } += entries
            val baseline = File(moduleDir, "api/$name.api")

            if (update) {
                baseline.parentFile.mkdirs()
                baseline.writeText(render(name, surface, spiVersion(root)))
                println("  → -Dspi.updateBaselines=true: rewrote ${baseline.relativeTo(root)}")
                continue
            }

            assertTrue(
                baseline.isFile,
                ":$name is published but has no surface baseline at ${baseline.relativeTo(root)}. " +
                    "Create it with `./gradlew :spi-compat:test -Dspi.updateBaselines=true`.",
            )
        }
        if (update) return

        val acceptedFile = File(root, ACCEPTED_BREAKS)
        val accepted = readAccepted(acceptedFile)
        val unaccepted = mutableSetOf<Pair<String, String>>()

        for ((name, moduleDir) in published) {
            val baseline = File(moduleDir, "api/$name.api")
            val gone = parse(baseline.readText()).mapNotNull { (owner, pinned) ->
                val missing = pinned
                    .filterNot { resolves(owner, it, whole) }
                    .filterNot { owner to it in accepted }
                if (missing.isEmpty()) return@mapNotNull null
                missing.forEach { unaccepted += owner to it }
                // A class that went entirely reads better as one line than as every member it had.
                val shown =
                    if (missing.size == pinned.size) listOf("the whole class, ${pinned.size} entries")
                    else missing
                owner to shown
            }
            if (gone.isNotEmpty()) problems += describe(name, gone)
        }

        // An acceptance that no longer describes anything is worse than none: it reads as a known break
        // while the member is back, and the next real removal of it passes unnoticed.
        val stale = accepted.filter { (owner, entry) -> resolves(owner, entry, whole) }
        assertTrue(
            stale.isEmpty(),
            "${acceptedFile.relativeTo(root)} accepts ${stale.size} break(s) that did not happen: the " +
                "member is in the build. Delete these lines.\n" +
                stale.sortedBy { it.first }.joinToString("\n") { "  ${it.first} ${it.second}" },
        )

        assertTrue(
            problems.isEmpty(),
            problems.joinToString("\n\n") + "\n\n" + WHAT_TO_DO + "\n\n" +
                "  • To record it as known and keep this test useful for the NEXT change, add each line " +
                "below to $ACCEPTED_BREAKS:\n" +
                unaccepted.sortedWith(compareBy({ it.first }, { it.second }))
                    .joinToString("\n") { "${it.first} ${it.second}" },
        )
    }

    /**
     * Whether a call compiled against `owner.entry` still links against [whole].
     *
     * Not simply "is the entry still declared on that class": the JVM resolves a method or a field through
     * the supertypes of the class named at the call site, so pulling a member UP into a supertype keeps
     * every existing caller working. A constructor and a static member are bound to the class that declares
     * them, and do not get that walk.
     */
    private fun resolves(
        owner: String,
        entry: String,
        whole: Map<String, Set<String>>,
        seen: MutableSet<String> = mutableSetOf(),
    ): Boolean {
        if (!seen.add(owner)) return false
        val current = whole[owner] ?: return false
        if (entry in current) return true
        val inheritable = (entry.startsWith("method ") || entry.startsWith("field ")) &&
            !entry.startsWith("method static") && !entry.startsWith("field static")
        if (!inheritable) return false
        // `abstract` is a property of the declaration, not of the call: a member that became abstract where
        // it was inherited still resolves.
        val wanted = entry.replace(" abstract ", " ")
        return current.asSequence()
            .filter { it.startsWith("super ") || it.startsWith("implements ") }
            .map { it.substringAfter(' ') }
            .any { parent ->
                whole[parent]?.any { it.replace(" abstract ", " ") == wanted } == true ||
                    resolves(parent, entry, whole, seen)
            }
    }

    /** Parsed `<owner> <entry>` lines: what this build is known and allowed to have dropped. */
    private fun readAccepted(file: File): Set<Pair<String, String>> {
        if (!file.isFile) return emptySet()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringBefore(' ') to it.substringAfter(' ') }
            .toSet()
    }

    /** What a reader of a failure needs, spelled out where they are already reading. */
    private companion object {
        /** Where a deliberate break is written down, so it is reviewed once and then stays quiet. */
        const val ACCEPTED_BREAKS = "plugins/spi-compat/accepted-breaks.txt"

        const val WHAT_TO_DO =
            "A plugin that names one of these was compiled against a version of the SPI that had it, and " +
                "links against this build at runtime: it loads, and then throws NoSuchMethodError or " +
                "NoClassDefFoundError at its first call.\n" +
                "  • To keep it: a top-level function that moved file keeps its facade with " +
                "`@file:JvmName(\"<OldFile>Kt\")` + `@file:JvmMultifileClass` on both halves; a member that " +
                "moved module keeps a deprecated forwarder where it was.\n" +
                "  • To break it deliberately: bump PLUGIN_API_VERSION, so ExternalPluginLoader refuses a " +
                "plugin built for the old API with a reason on its row instead of letting it fail mid-" +
                "session, note the change in the SPI migration doc, and regenerate the baselines with " +
                "`./gradlew :spi-compat:test -Dspi.updateBaselines=true`."
    }

    private fun describe(module: String, gone: List<Pair<String, List<String>>>): String = buildString {
        val count = gone.sumOf { it.second.size }
        append(":$module — $count pinned ")
        append(if (count == 1) "member is" else "members are")
        append(" no longer anywhere in the published SPI:")
        for ((owner, entries) in gone.sortedBy { it.first }) {
            append("\n  $owner")
            for (entry in entries.sorted()) append("\n      $entry")
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Reading the compiled surface
    // -----------------------------------------------------------------------------------------------

    /**
     * Every pinnable class in [roots] (a module's jar, or its compiled-classes directory, whichever Gradle
     * put on the test runtime classpath), mapped to its sorted entries.
     *
     * The bytecode is READ, never loaded: a module's `compileOnly` dependencies are absent here by design
     * (`:plugin-ui-api` names Compose types in its signatures), and a reflective walk would fail on exactly
     * those classes rather than pin them.
     */
    private fun readSurface(roots: List<File>): Map<String, Set<String>> {
        val surface = mutableMapOf<String, Set<String>>()
        for (root in roots) {
            when {
                root.isDirectory -> root.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .forEach { file -> classSurface(file.readBytes())?.let { surface += it } }

                root.isFile && root.extension == "jar" -> ZipFile(root).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.endsWith(".class") }
                        .forEach { entry ->
                            classSurface(zip.getInputStream(entry).use { it.readBytes() })
                                ?.let { surface += it }
                        }
                }
            }
        }
        return surface
    }

    /** One class's pinned entries, or null when the class is not part of any plugin's linkable surface. */
    private fun classSurface(bytes: ByteArray): Pair<String, Set<String>>? {
        var owner: String? = null
        val entries = mutableSetOf<String>()
        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                if (!pinnableClass(access, name)) return
                owner = name
                entries += "kind ${kindOf(access)}"
                // An interface's `super` is always java/lang/Object and says nothing; a class's does.
                if (superName != null && access and Opcodes.ACC_INTERFACE == 0) entries += "super $superName"
                interfaces?.forEach { entries += "implements $it" }
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (owner != null && pinnableMember(access, name)) {
                    val kind = if (name == "<init>") "ctor" else "method"
                    val label = if (name == "<init>") descriptor else "$name$descriptor"
                    entries += "$kind ${modifiers(access)}$label"
                }
                return null
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                if (owner != null && pinnableMember(access, name)) {
                    entries += "field ${modifiers(access)}$name $descriptor"
                }
                return null
            }
        }
        ClassReader(bytes).accept(
            visitor,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return owner?.let { it to entries }
    }

    /**
     * Whether a class is something a plugin can link to.
     *
     * Nested classes are kept (`Coordinate$Companion` is named in plugin code all the time); the anonymous
     * classes Kotlin generates for lambdas and object expressions are not, and they are recognised by a
     * `$`-segment that is a number.
     */
    private fun pinnableClass(access: Int, name: String): Boolean {
        if (access and Opcodes.ACC_PUBLIC == 0) return false
        if (access and Opcodes.ACC_SYNTHETIC != 0) return false
        return name.split('$').drop(1).none { it.isEmpty() || it.all(Char::isDigit) }
    }

    /**
     * Whether a member is something a plugin's compiled form can reference.
     *
     * The `$` rule drops what the compiler names for itself — `internal` members carry a mangled
     * `$module_name` suffix, and property annotations land on a `$annotations` holder — with two deliberate
     * exceptions, because both ARE what a caller links to:
     *
     *  * `foo$default`, the bridge a call that omits a defaulted argument compiles to. Adding a parameter
     *    with a default changes its descriptor, which breaks every existing caller and nothing else here
     *    would notice;
     *  * `<init>`, including the synthetic constructor generated for a class with default arguments, which
     *    is what a plugin constructing a `PluginManifest` by name actually calls.
     */
    private fun pinnableMember(access: Int, name: String): Boolean {
        if (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) return false
        if (access and Opcodes.ACC_BRIDGE != 0) return false
        if (name == "<init>") return true
        if (access and Opcodes.ACC_SYNTHETIC != 0 && !name.endsWith("\$default")) return false
        return '$' !in name.removeSuffix("\$default")
    }

    private fun kindOf(access: Int): String = when {
        access and Opcodes.ACC_ANNOTATION != 0 -> "annotation"
        access and Opcodes.ACC_INTERFACE != 0 -> "interface"
        access and Opcodes.ACC_ENUM != 0 -> "enum"
        else -> "class"
    }

    /** Only the modifiers whose change is a linkage break for a caller. */
    private fun modifiers(access: Int): String = buildString {
        if (access and Opcodes.ACC_STATIC != 0) append("static ")
        if (access and Opcodes.ACC_ABSTRACT != 0) append("abstract ")
    }

    // -----------------------------------------------------------------------------------------------
    // The baseline file
    // -----------------------------------------------------------------------------------------------

    private fun render(module: String, surface: Map<String, Set<String>>, spiVersion: String): String =
        buildString {
            appendLine("# The compiled surface of io.github.tyron12233:$module, pinned at SPI $spiVersion.")
            appendLine("# A plugin links against these by name and descriptor; see :spi-compat.")
            appendLine("# Regenerate with: ./gradlew :spi-compat:test -Dspi.updateBaselines=true")
            for (owner in surface.keys.sorted()) {
                appendLine(owner)
                for (entry in surface.getValue(owner).sortedWith(entryOrder)) appendLine("\t$entry")
            }
        }

    private val entryKinds = listOf("kind", "super", "implements", "ctor", "field", "method")

    /** What a class IS before what it has, so a baseline reads top-down rather than alphabetically. */
    private val entryOrder: Comparator<String> = compareBy(
        { entry -> entryKinds.indexOfFirst { entry.startsWith("$it ") } },
        { entry -> entry },
    )

    private fun parse(text: String): Map<String, Set<String>> {
        val parsed = linkedMapOf<String, MutableSet<String>>()
        var owner: MutableSet<String>? = null
        for (line in text.lineSequence()) {
            when {
                line.isBlank() || line.startsWith("#") -> Unit
                line.startsWith("\t") -> {
                    val entries = checkNotNull(owner) { "malformed baseline: entry before any class name" }
                    entries += line.trimStart('\t')
                }

                else -> owner = parsed.getOrPut(line.trim()) { mutableSetOf() }
            }
        }
        return parsed
    }

    // -----------------------------------------------------------------------------------------------
    // The checkout
    // -----------------------------------------------------------------------------------------------

    /** Published module name to its directory, found the same way `PluginBomTest` finds it: by opt-in. */
    private fun publishedModules(root: File): Map<String, File> {
        val depthOne = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        val depthTwo = depthOne.flatMap { it.listFiles().orEmpty().filter(File::isDirectory) }
        return (depthOne + depthTwo)
            .filter { File(it, "build.gradle.kts").isFile }
            .filter { module ->
                val build = File(module, "build.gradle.kts").readText()
                ("dev.ide.spi-publish" in build || "dev.ide.spi-pom" in build) &&
                    // A `java-platform` has no classes to pin.
                    module.name != "plugin-bom"
            }
            .associate { it.name to it }
            .toSortedMap()
    }

    /** The version the baselines record, read from the constant every other consumer of it reads. */
    private fun spiVersion(root: File): String {
        val source = File(root, "plugins/plugin-api/src/main/kotlin/dev/ide/plugin/PluginManifest.kt")
        val match = Regex("""PLUGIN_SPI_VERSION:\s*String\s*=\s*"([^"]+)"""").find(source.readText())
        return match?.groupValues?.get(1) ?: "unknown"
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
    }

    /** Paths compare with `/` separators so the classpath match reads the same on every host. */
    private fun File.invariantPath(): String = absolutePath.replace(File.separatorChar, '/')
}
