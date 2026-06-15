package dev.ide.android.support.resources

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** A parsed `AndroidManifest.xml`: the bits the IDE needs for facets, `BuildConfig`, and analysis. */
data class AndroidManifestInfo(
    /** `package` attribute (the legacy R/BuildConfig package); may be absent when the namespace is in Gradle. */
    val packageName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val components: List<ManifestComponent>,
)

/** A declared component — its [kind] (`activity`/`service`/`receiver`/`provider`) and fully-qualified [name]. */
data class ManifestComponent(val kind: String, val name: String)

/** Stdlib parser for `AndroidManifest.xml` (JAXP DOM; runs on desktop and ART). */
object AndroidManifestParser {

    private val COMPONENT_TAGS = setOf("activity", "service", "receiver", "provider")

    fun parse(file: Path): AndroidManifestInfo? {
        if (!Files.isRegularFile(file)) return null
        val doc = runCatching { documentBuilder().parse(file.toFile()) }.getOrNull() ?: return null
        val manifest = doc.documentElement ?: return null
        val pkg = manifest.getAttribute("package").ifEmpty { null }

        var minSdk: Int? = null
        var targetSdk: Int? = null
        val permissions = ArrayList<String>()
        val components = ArrayList<ManifestComponent>()

        forEachDescendant(manifest) { el ->
            when (el.tagName) {
                "uses-sdk" -> {
                    minSdk = el.androidAttr("minSdkVersion")?.toIntOrNull() ?: minSdk
                    targetSdk = el.androidAttr("targetSdkVersion")?.toIntOrNull() ?: targetSdk
                }
                "uses-permission" -> el.androidAttr("name")?.let { permissions += it }
                in COMPONENT_TAGS -> el.androidAttr("name")?.let { components += ManifestComponent(el.tagName, resolveName(it, pkg)) }
            }
        }
        return AndroidManifestInfo(pkg, minSdk, targetSdk, permissions.distinct(), components)
    }

    /** A component `android:name` may be relative (`.MainActivity`) or bare — resolve it against [pkg]. */
    private fun resolveName(name: String, pkg: String?): String = when {
        name.startsWith(".") && pkg != null -> pkg + name
        !name.contains('.') && pkg != null -> "$pkg.$name"
        else -> name
    }

    private fun Element.androidAttr(local: String): String? =
        (getAttribute("android:$local").ifEmpty { getAttribute(local) }).ifEmpty { null }

    private fun forEachDescendant(root: Element, visit: (Element) -> Unit) {
        val children = root.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            visit(el)
            forEachDescendant(el, visit)
        }
    }

    private fun documentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder()
}
