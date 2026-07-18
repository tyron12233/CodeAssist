package dev.ide.android.support.tasks

import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskResult
import dev.ide.build.engine.TaskInputsImpl
import dev.ide.build.engine.TaskOutputsImpl
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * `injectAppLogProvider`: on a DEBUG build, weave a `<provider>` for the IDE's log bridge
 * ([dev.ide.android.support.tools.AndroidAppLogRuntime]) into the merged manifest, producing a separate
 * instrumented manifest that `aapt2 link` consumes instead of the plain merged one. The bridge's
 * `ContentProvider.onCreate` runs early in the app's startup and forwards its logs to the IDE.
 *
 * Kept as a distinct output ([outManifest], not an in-place rewrite of [mergedManifest]) so the un-instrumented
 * merged manifest stays available for other consumers (e.g. the layout preview relink), and so the build graph
 * has a clean producer→consumer edge rather than two tasks writing the same file.
 */
internal class InjectAppLogProviderTask(
    override val name: TaskName,
    private val mergedManifest: Path,
    private val providerClass: String,
    private val authority: String,
    private val outManifest: Path,
) : Task {
    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {
            filePaths("manifest", listOf(mergedManifest).filter { Files.exists(it) })
            property("provider", providerClass)
            property("authority", authority)
        }
    override val outputs: TaskOutputs get() = TaskOutputsImpl().apply { filePath("manifest", outManifest) }

    override suspend fun execute(ctx: TaskContext): TaskResult {
        ctx.checkCanceled()
        if (!Files.isRegularFile(mergedManifest))
            return TaskResult.Failed("merged manifest not found: $mergedManifest")

        val doc = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(mergedManifest.toFile())
        } catch (t: Throwable) {
            return TaskResult.Failed("app-log injection could not parse the merged manifest: ${t.message}", t)
        }

        val application = firstChildTag(doc.documentElement, "application")
        if (application == null) {
            // No <application> — nothing to instrument; pass the manifest through untouched.
            outManifest.parent?.let { Files.createDirectories(it) }
            Files.copy(mergedManifest, outManifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            ctx.logger()("injectAppLogProvider: no <application> element — skipped")
            return TaskResult.Success
        }

        // Idempotent: don't add a second provider if one is already present (e.g. a hand-edited manifest).
        val alreadyPresent = childElements(application).any {
            it.tagName == "provider" && it.androidAttr("name") == providerClass
        }
        if (!alreadyPresent) {
            val provider = doc.createElement("provider")
            provider.setAttribute("android:name", providerClass)
            provider.setAttribute("android:authorities", authority)
            provider.setAttribute("android:exported", "false")
            // A high initOrder boots the bridge before lower-order providers, so early startup logs are captured.
            provider.setAttribute("android:initOrder", "2147483646")
            application.appendChild(provider)
        }

        outManifest.parent?.let { Files.createDirectories(it) }
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        Files.newOutputStream(outManifest).use { transformer.transform(DOMSource(doc), StreamResult(it)) }
        ctx.logger()("injectAppLogProvider -> ${outManifest.fileName} (provider $providerClass, authority $authority)")
        return TaskResult.Success
    }

    private fun firstChildTag(parent: Element?, tag: String): Element? =
        parent?.let { childElements(it).firstOrNull { el -> el.tagName == tag } }

    private fun childElements(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) (children.item(i) as? Element)?.let { out += it }
        return out
    }

    private fun Element.androidAttr(local: String): String? =
        (getAttribute("android:$local").ifEmpty { getAttribute(local) }).ifEmpty { null }
}
