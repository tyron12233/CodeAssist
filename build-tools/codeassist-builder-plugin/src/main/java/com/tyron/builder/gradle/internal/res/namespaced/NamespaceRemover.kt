package com.tyron.builder.gradle.internal.res.namespaced

import com.android.SdkConstants
import com.android.ide.common.resources.CompileResourceRequest
import com.android.ide.common.resources.ResourceCompilationService
import com.android.ide.common.xml.XmlFormatPreferences
import com.android.ide.common.xml.XmlFormatStyle
import com.android.ide.common.xml.XmlPrettyPrinter
import com.android.utils.FileUtils
import com.android.utils.PositionXmlParser
import com.google.common.annotations.VisibleForTesting
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.ParserConfigurationException

object NamespaceRemover : ResourceCompilationService {

    @Throws(Exception::class)
    override fun submitCompile(request: CompileResourceRequest) {
        val input = request.inputFile
        val output = compileOutputFor(request)
        FileUtils.mkdirs(output.parentFile)

        if (input.name.endsWith(SdkConstants.DOT_XML)) {
            // Remove namespace.
            rewrite(input.toPath(), output.toPath())
        } else {
            // Just copy the file.
            FileUtils.copyFile(input, output)
        }

    }

    override fun compileOutputFor(request: CompileResourceRequest): File {
        val parentDir = File(request.outputDirectory, request.inputDirectoryName)
        return File(parentDir, request.inputFile.name)
    }

    @Throws(IOException::class)
    override fun close() {
        // As this currently does work on submission, no need to wait for anything.
    }

    /*
     * Rewrites an XML file to be namespace free.
     */
    @Throws(IOException::class, ParserConfigurationException::class, SAXException::class)
    fun rewrite(input: Path, output: Path) {
        BufferedInputStream(
                Files.newInputStream(input)).use {
            `is` -> Files.write(output, rewrite(`is`).toByteArray())
        }
    }

    /*
     * Rewrites an XML document to be namespace free (AAPT1 compliant).
     */
    @VisibleForTesting
    @Throws(ParserConfigurationException::class, SAXException::class, IOException::class)
    fun rewrite(input: InputStream, lineSeparator: String = System.lineSeparator()): String {
        val doc = PositionXmlParser.parse(input)

        removeNamespaces(doc)

        return XmlPrettyPrinter.prettyPrint(
                doc,
                XmlFormatPreferences.defaults(),
                XmlFormatStyle.get(doc),
                lineSeparator,
                false)
    }

    /*
     * Removes all non-android and non-tools namespaces from a node and replaces them with the
     * non-namespace defaults ('res-auto' for the URI and no namespaces for the references).
     */
    private fun removeNamespaces(node: Node) {
        if (node.nodeType == Node.TEXT_NODE) {
            // We could be inside a reference that could be namespaced. Remove all namespaces other
            // than "android" and "tools".
            val content = node.textContent
            val nonNamespacedContent = removeNamespace(content)
            if (content != nonNamespacedContent) {
                node.textContent = nonNamespacedContent
            }
        } else if (node.nodeType == Node.ATTRIBUTE_NODE) {
            val prefix = node.prefix
            if (prefix != null && prefix == "xmlns") {
                // We found a definition of a namespace. Leave "android", "tools" and "aapt" intact.
                // We will leave the namespace unchanged, but instead change the URI to res-auto.
                val uri = node.textContent
                if (uri != SdkConstants.ANDROID_URI
                        && uri != SdkConstants.TOOLS_URI
                        && uri != SdkConstants.AAPT_URI) {
                    node.textContent = SdkConstants.AUTO_URI
                }
            }
        }

        // First fix the attributes.
        val attributes = node.attributes
        run {
            var i = 0
            while (attributes != null && i < attributes.length) {
                removeNamespaces(attributes.item(i))
                ++i
            }
        }

        // Now fix the children.
        val children = node.childNodes
        for (i in 0 until children.length) {
            removeNamespaces(children.item(i))
        }
    }

    /*
     * Find all references to a namespace and remove them, but ignore "android" and "tools" ones.
     * For example:
     * "@lib:string/foo" becomes "@string/foo"
     * "@com.foo.bar:id/beep" becomes "@id/beep"
     * "@android:id/name" stays the same
     * "@tools:id/setting" stays the same
     *
     * @returns non-namespaced content (AAPT1 compliant). Can be the same as input if there were no
     *          namespaced references.
     */
    private fun removeNamespace(content: String): String {
        if (content.startsWith("@") && content.contains(":")) {
            val ns = content.substring(1, content.indexOf(':'))

            if (ns != SdkConstants.ANDROID_NS_NAME && ns != SdkConstants.TOOLS_NS_NAME) {
                return "@" + content.substring(content.indexOf(':') + 1)
            }
        }
        return content
    }
}
