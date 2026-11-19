package dev.ide.preview.layoutlib

import java.io.File

/**
 * Where layoutlib's runtime files are: the data it loads at start (build.prop, fonts, ICU, hyphenation,
 * keyboard layouts), its native libraries, and the framework's own resources.
 *
 * ```
 * <home>/build.prop
 * <home>/data/{fonts,icu,hyphen-data,keyboards}/
 * <home>/native/                 layoutlib_jni + libandroid_runtime for this host
 * <home>/framework-res-raw/res/  the framework resources as layoutlib-resources ships them
 * <home>/framework-res/res/      the same, prepared by [frameworkRes]
 * ```
 */
class LayoutlibHome(
    val dir: File,
    /**
     * Where `layoutlib_jni` and `libandroid_runtime` are. Beside the data on a desktop; on iOS a library can
     * only be loaded from the app's own signed Frameworks folder, which is nowhere near it.
     */
    val nativeDir: File = File(dir, "native"),
) {
    val buildProp: File get() = File(dir, "build.prop")
    val fonts: File get() = File(dir, "data/fonts")
    val hyphenData: File get() = File(dir, "data/hyphen-data")

    /** The ICU data file; its name carries the ICU version, so it is found rather than named. */
    val icuData: File
        get() = File(dir, "data/icu").listFiles { f -> f.name.endsWith(".dat") }?.firstOrNull()
            ?: error("no ICU data under ${File(dir, "data/icu")}")

    val keyboards: List<File>
        get() = File(dir, "data/keyboards").listFiles { f -> f.name.endsWith(".kcm") }?.sorted().orEmpty()

    /**
     * The framework resources in the form the resource loader can read, made from the raw copy the first time.
     *
     * The loader is the one an app build uses, and the framework's own tree is not an app's: it holds tags only
     * the platform build knows (`java-symbol`, the staging `public` groups), which end the load, and whole
     * folders for products this preview never is (watch, television, car), which the best-match rule would
     * otherwise pick a drawable from. Translations are left out too: 600 folders of strings a preview shows in
     * one language, which is most of the parsing.
     */
    fun frameworkRes(): File {
        val ready = File(dir, "framework-res/res")
        val stamp = File(dir, "framework-res/.prepared")
        if (stamp.isFile && ready.isDirectory) return ready
        val raw = File(dir, "framework-res-raw/res")
        check(raw.isDirectory) { "no framework resources at $raw" }
        ready.deleteRecursively()
        ready.mkdirs()
        for (folder in raw.listFiles().orEmpty().sortedBy { it.name }) {
            if (!folder.isDirectory || skipped(folder.name)) continue
            val target = File(ready, folder.name).apply { mkdirs() }
            for (file in folder.listFiles().orEmpty()) {
                if (folder.name.startsWith("values")) {
                    if (file.name in PLATFORM_ONLY_FILES) continue
                    val text = file.readText()
                    File(target, file.name).writeText(if ("<java-symbol" in text) JAVA_SYMBOL.replace(text, "") else text)
                } else {
                    file.copyTo(File(target, file.name), overwrite = true)
                }
            }
        }
        stamp.writeText("1")
        return ready
    }

    private fun skipped(folder: String): Boolean {
        val qualifiers = folder.split('-').drop(1)
        if (qualifiers.any { it in OTHER_PRODUCTS }) return true
        if (!folder.startsWith("values-")) return false
        val first = qualifiers.first()
        // A language: two or three lower-case letters, or a BCP 47 tag. `values-night`, `-land`, `-sw600dp`, `-v31` stay.
        return first.startsWith("b+") || (first.length in 2..3 && first.all { it in 'a'..'z' } && first !in NOT_LANGUAGES)
    }

    private companion object {
        val PLATFORM_ONLY_FILES = setOf("symbols.xml", "public-staging.xml", "public-final.xml")
        val JAVA_SYMBOL = Regex("""<java-symbol[^>]*/>""")
        val OTHER_PRODUCTS = setOf("watch", "television", "car", "appliance", "desk", "vrheadset")
        val NOT_LANGUAGES = setOf("ldp", "any", "nav", "dpi")
    }
}
