package com.tyron.builder.gradle.internal.res.namespaced

import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.symbols.exportToCompiledJava
import com.tyron.builder.utils.zipEntry
import com.android.ide.common.symbols.Symbol
import com.android.ide.common.symbols.SymbolIo
import com.android.ide.common.symbols.SymbolTable
import com.android.ide.common.symbols.canonicalizeValueResourceName
import com.android.ide.common.xml.XmlFormatPreferences
import com.android.ide.common.xml.XmlFormatStyle
import com.android.ide.common.xml.XmlPrettyPrinter
import com.android.io.nonClosing
import com.android.resources.NamespaceReferenceRewriter
import com.android.utils.forEach
import com.android.resources.ResourceType
import com.android.utils.PositionXmlParser
import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.google.common.io.ByteStreams
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.HashSet
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rewrites non-namespaced resource references to be namespace aware.
 *
 * @param symbolTables a list of symbol tables for the current module and its' dependencies. The
 *      order matters, the closest modules are at the front, the furthest are at the end. The first
 *      symbol table should be for the module from which the transformed classes come from.
 */
class NamespaceRewriter(
    private val symbolTables: ImmutableList<SymbolTable>,
    private val logger: Logger = Logging.getLogger(NamespaceRewriter::class.java)) {

    private val localPackage = symbolTables.firstOrNull()?.tablePackage ?: ""
    private val referenceRewriter =
        NamespaceReferenceRewriter(localPackage, this::findPackage)

    fun rewriteClass(clazz: Path, output: Path) {
        // First read the class and re-write the R class resource references.
        val originalClass = Files.readAllBytes(clazz)
        val rewrittenClass = rewriteClass(originalClass)
        Files.write(output, rewrittenClass)
    }

    private fun rewriteClass(originalClass: ByteArray) : ByteArray {
        val cw = ClassWriter(0)
        val crw = ClassReWriter(Opcodes.ASM7, cw)
        val cr = ClassReader(originalClass)
        cr.accept(crw, 0)
        // Write inner R classes references.
        crw.writeInnerRClasses()
        return cw.toByteArray()
    }

     /**
     * Rewrites all classes from the input JAR file to be fully resource namespace aware and places
     * them in the output JAR; it will also filter out all non .class files, so that the output JAR
     * contains only the namespaced classes.
     *
     * Does not close either the input or output streams.
     */
    fun rewriteJar(inputJarStream: InputStream, outputJarStream: OutputStream) {
        ZipInputStream(inputJarStream.nonClosing()).use { input ->
            ZipOutputStream(outputJarStream.nonClosing()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name
                    if (!name.endsWith(".class")) continue
                    output.putNextEntry(zipEntry(name))
                    output.write(rewriteClass(ByteStreams.toByteArray(input)))
                }
            }
        }
    }

    /**
     * Rewrites the input file to be fully namespaced using the provided method. Writes fully
     * namespaced document to the output.
     */
    private inline fun rewriteFile(
        input: InputStream,
        output: OutputStream,
        displayInput: Any,
        method: (node: Document) -> Unit
    ) {
        // Read the file.
        val doc = try {
            PositionXmlParser.parse(input.nonClosing())
        } catch (e: Exception) {
            throw IOException("Failed to parse $displayInput", e)
        }

        // Fix namespaces.
        try {
            method(doc)
        } catch (e: Exception) {
            throw IOException("Failed namespace $displayInput", e)
        }

        // Write the new file. The PositionXmlParser uses UTF_8 when reading the file, so it
        // should be fine to write as UTF_8 too.

        output.nonClosing().writer(Charsets.UTF_8).use {
            it.write(
                XmlPrettyPrinter
                    .prettyPrint(
                        doc,
                        XmlFormatPreferences.defaults(),
                        XmlFormatStyle.get(doc),
                        System.lineSeparator(),
                        false
                    )
            )
        }
    }



    /**
     * Rewrites the AndroidManifest.xml file to be fully resource namespace aware. Finds all
     * resource references (e.g. '@string/app_name') and makes them namespace aware (e.g.
     * '@com.foo.bar:string/app_name').
     * This will also append the package to the references to resources from this library - it is
     * not necessary, but saves us from comparing the package names.
     */
    fun rewriteManifest(
        inputManifest: InputStream,
        outputManifest: OutputStream,
        displayInput: Any
    ) {
        rewriteFile(
            inputManifest,
            outputManifest,
            displayInput,
            referenceRewriter::rewriteManifestNode
        )
    }

    /**
     * Rewrites a values file to be fully resource namespace aware. Finds all resource references
     * and makes them namespace aware, for example:
     * - simple references, e.g. '@string/app_name' becomes '@com.foo.bar:string/app_name'
     * - styles' parents, e.g. 'parent="@style/Parent', 'parent="Parent"' both become
     *   'parent="@com.foo.bar:style/Parent"'
     * - styles' item name references, e.g. 'item name="my_attr"' becomes
     *   'item name="com.foo.bar:my_attr" (no "@" or "attr/" in this case)
     * This will also append the package to the references to resources from this library - it is
     * not necessary, but saves us from comparing the package names.
     */
    fun rewriteValuesFile(input: InputStream, output: OutputStream) {
        rewriteFile(input, output, input, this::rewriteValuesNode)
    }

    private fun rewriteValuesNode(node: Node) {
        if (node.nodeType == Node.TEXT_NODE) {
            // The content could be a resource reference. If it is not, do not update the content.
            val content = node.nodeValue
            val (namespacedContent, _) = referenceRewriter.rewritePossibleReference(content)
            if (content != namespacedContent) {
                node.nodeValue = namespacedContent
            }
        } else if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "style") {
            // Styles need to be handled separately.
            rewriteStyleElement(node as Element)
            return
        } else if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == "declare-styleable") {
            rewriteStyleableElement(node as Element)
            return
        }

        // First fix the attributes.
        node.attributes?.forEach {
            rewriteValuesNode(it)
        }

        // Now fix the children.
        node.childNodes?.forEach {
            rewriteValuesNode(it)
        }
    }

    /** Rewrites a style element
     *
     * e.g.
     * ```
     * <style name="AppTheme" parent="Theme.AppCompat.Light.DarkActionBar">
     *     <item name="colorPrimary">@color/colorPrimary</item>
     * </style>
     * ```
     * to
     * ```
     * <style name="AppTheme" parent="android.support.v7.appcompat:Theme.AppCompat.Light.DarkActionBar">
     *     <item name="android.support.v7.appcompat:colorPrimary">@com.example.app:color/colorPrimary</item>
     * </style>
     * ```
     * */
    private fun rewriteStyleElement(element: Element) {
        rewriteParent(element, "style")
        rewriteStyleItems(element)
    }

    private fun rewriteParent(element: Element, type: String) {
        val originalParent: String? = element.attributes.getNamedItem("parent")?.nodeValue
        var parent: String? = null
        if (originalParent == null) {
            // Guess, maybe we have an implicit parent?
            val name: String = element.attributes.getNamedItem("name")!!.nodeValue
            val possibleParent = name.substringBeforeLast('.', "")
            if (!possibleParent.isEmpty()) {
                val possiblePackage = maybeFindPackage(
                    type = type,
                    name = possibleParent,
                    reversed = false
                )
                if (possiblePackage != null) {
                    parent = "@*$possiblePackage:$type/$possibleParent"
                }
            }
        } else if (originalParent.isEmpty() || (originalParent.contains(':'))) {
            // leave it alone, there is explicitly no parent or we already have a namespace (most
            // likely "android:").
        } else {
            // Rewrite explicitly included parents
            parent = originalParent
            if (!parent.startsWith("@")) {
                parent = "@$type/$parent"
            }
            val (rewrittenParent, _) = referenceRewriter.rewritePossibleReference(parent)
            parent = rewrittenParent
        }
        if (parent != null && parent != originalParent) {
            val parentAttribute = element.ownerDocument.createAttribute("parent")
            parentAttribute.value = parent
            element.attributes.setNamedItem(parentAttribute)
        }
    }

    private fun removeParent(element: Element) {
        element.removeAttribute("parent")
    }

    private fun rewriteStyleableElement(element: Element) {
        // Remove the parent, if it exists. The 'parent' tag in a declare-styleable doesn't actually
        // do anything, so it's okay to just remove them.
        removeParent(element)

        // Take care of the styleable children.
        element.childNodes.forEach { child ->
            if (child.nodeType == Node.ELEMENT_NODE &&
                (child.nodeName == "attr" || child.nodeName == "item")) {
                child as Element
                child.attributes.forEach {
                    // Only handle name nodes and skip if already has a package.
                    if (it.nodeName == "name" && !it.nodeValue.contains(":")) {
                        val foundPackage = findPackageForAttr(it.nodeValue)
                        if (foundPackage != localPackage) {
                            it.nodeValue = "*$foundPackage:${it.nodeValue}"
                        }
                    }
                }
            }
        }
    }

    private fun rewriteStyleItems(styleElement: Element) {
        styleElement.childNodes?.forEach {
            if (it.nodeType == Node.ELEMENT_NODE && it.nodeName == "item") {
                rewriteStyleItem(it as Element)
            }
        }
    }

    private fun rewriteStyleItem(styleItemElement: Element) {
        styleItemElement.attributes.forEach { attribute ->
            if (attribute.nodeName == "name") {
                rewriteStyleItemNameAttribute(attribute)
            }
        }
        styleItemElement.childNodes.forEach { node ->
            if (node.nodeType == Node.TEXT_NODE) {
                rewriteStyleItemValue(node)
            }
        }
    }

    private fun rewriteStyleItemNameAttribute(attribute: Node) {
        if (attribute.nodeValue.contains(':')) {
            return
        }
        // If the name is not from the "android:" namespace, it comes from this library or its
        // dependencies (uncommon but needs to be handled).
        val content = "@attr/${attribute.nodeValue}"
        val (namespacedContent, foundPackage) = referenceRewriter.rewritePossibleReference(content)
        if (content != namespacedContent) {
            // Prepend the package to the content, keep the "*" symbol for visibility.
            attribute.nodeValue = "*$foundPackage:${attribute.nodeValue}"
        }
    }

    private fun rewriteStyleItemValue(node: Node) {
        // The content could be a resource reference. If it is not, do not update the content.
        val content = node.nodeValue
        val (namespacedContent, _) = referenceRewriter.rewritePossibleReference(content)
        if (content != namespacedContent) {
            node.nodeValue = namespacedContent
        }
    }

    /**
     * Rewrites an XML file (e.g. layout) to be fully resource namespace aware. Finds all resource
     * references and makes them namespace aware, for example:
     * - simple references, e.g. '@string/app_name' becomes '@com.foo.bar:string/app_name'
     * - adds XML namespaces for dependencies (e.g. xmlns:android_support_constraint=
     *   "http://schemas.android.com/apk/res/android.support.constraint")
     * - updates XML namespaces from to the correct package, e.g  app:layout_constraintLeft_toLeftOf
     *   becomes android_support_constraint:layout_constraintLeft_toLeftOf
     * - removes res-auto namespace to make sure we don't leave anything up to luck
     * This will also append the package to the references to resources from this library - it is
     * not necessary, but saves us from comparing the package names.
     */
    fun rewriteXmlFile(input: InputStream, output: OutputStream) {
        rewriteFile(input, output, input, this::rewriteXmlDoc)
    }

    /**
     * Rewrites all the resources from an exploded-aar input directory as passed in input to the
     * output directory.
     *
     * * Values files are processed with [#rewriteValuesFile]
     * * XML files not in raw (such as layouts) are processed with [#rewriteXmlFile]
     * * Everything else is copied as-is
     */
    fun rewriteAarResource(name: String, input: InputStream, output: OutputStream) {
        when {
            name.startsWith("res/values/") || name.startsWith("res/values-") -> {
                rewriteValuesFile(input, output)
            }
            name.startsWith("res/raw/") || name.startsWith("res/raw-") -> {
                throw IllegalArgumentException("Raw resources do not need rewriting")
            }
            name.endsWith(".xml") -> {
                rewriteXmlFile(input, output)
            }
            else -> {
                throw IllegalArgumentException("Non-xml resources do not need rewriting")
            }
        }
    }

    private fun rewriteXmlDoc(document: Document) {
        // Get the main node. Can be a 'layout' or a 'vector' etc. This is where we will add all the
        // namespaces.
        val mainNode = getMainElement(document)

        // TODO(b/110036551): can 'res-auto' be declared anywhere deeper than the main node?
        // First, find any namespaces we need to fix - any pointing to 'res-auto'. Usually it is
        // only "xmlns:app", but let's be safe here.
        val namespacesToFix: HashSet<String> = HashSet()
        // We need to collect which of the dependencies packages have we used, so that we can define
        // the corresponding XML namespaces.
        val usedNamespaces: HashMap<String, String> = HashMap()
        collectAndRemoveXmlNamespaces(mainNode, namespacesToFix)

        // First fix the attributes.
        mainNode.attributes?.forEach {
            if (!it.nodeName.startsWith("xmlns:")) {
                rewriteXmlNode(it, document, namespacesToFix, usedNamespaces)
            }
        }

        // Now fix the children.
        mainNode.childNodes?.forEach {
            rewriteXmlNode(it, document, namespacesToFix, usedNamespaces)
        }

        // Finally add the used namespaces.
        for ((pckg, namespace) in usedNamespaces.toSortedMap()) {
            mainNode.setAttribute(
                "xmlns:${namespace.replace('.', '_')}",
                "http://schemas.android.com/apk/res/$pckg"
            )
        }
    }

    /**
     * Goes through all nodes of the document and removes "res-auto" namespaces, while collecting
     * their XML names.
     */
    private fun collectAndRemoveXmlNamespaces(node: Element, namespacesToFix: HashSet<String>) {
        node.attributes?.forEach {
            if (it.nodeName.startsWith("xmlns:")
                && it.nodeValue == "http://schemas.android.com/apk/res-auto") {
                val ns = it.nodeName.substringAfter("xmlns:")
                namespacesToFix.add(ns)
                node.removeAttribute("xmlns:$ns")
            }
        }

        node.childNodes?.forEach {
            if (it is Element)
                collectAndRemoveXmlNamespaces(it, namespacesToFix)
        }
    }

    /** Resource XML files should have only one main element
     * everything else should be whitespace and comments */
    private fun getMainElement(document: Document): Element {
        var candidateMainNode: Element? = null

        document.childNodes?.forEach {
            if (it.nodeType == Node.ELEMENT_NODE) {
                if (candidateMainNode != null) {
                    error("Invalid XML file - there can only be one main node.")
                }
                candidateMainNode = it as Element
            }
        }

        return candidateMainNode ?: error("Invalid XML file - missing main node.")
    }

    private fun rewriteXmlNode(
        node: Node, document: Document,
        namespacesToFix: HashSet<String>, usedNamespaces: HashMap<String, String>
    ) {
        if (node.nodeType == Node.TEXT_NODE) {
            // The content could be a resource reference. If it is not, do not update the content.
            val content = node.nodeValue
            val (namespacedContent, _) = referenceRewriter.rewritePossibleReference(content)
            if (content != namespacedContent) {
                node.nodeValue = namespacedContent
            }
        } else if (node.nodeType == Node.ATTRIBUTE_NODE && node.nodeName.contains(":")) {
            // Only fix res-auto.
            if (namespacesToFix.any { node.nodeName.startsWith("$it:") }) {
                val name = node.nodeName.substringAfter(':')
                val content = "@attr/$name"
                // We need to keep the XML namespace, even if it's local.
                val (namespacedContent, foundPackage) =
                        referenceRewriter.rewritePossibleReference(content, true)
                if (content != namespacedContent) {
                    usedNamespaces.computeIfAbsent(foundPackage, { "ns${usedNamespaces.size}" })
                    document.renameNode(
                        node,
                        "http://schemas.android/apk/res/$foundPackage",
                        "${usedNamespaces[foundPackage]!!}:$name"
                    )
                }
            }
        }

        // First fix the attributes.
        node.attributes?.forEach {
            rewriteXmlNode(it, document, namespacesToFix, usedNamespaces)
        }

        // Now fix the children.
        node.childNodes?.forEach {
            rewriteXmlNode(it, document, namespacesToFix, usedNamespaces)
        }
    }

    /**
     * Writes the bytecode of the R class to the specified output JAR file, making sure all
     * resources are properly namespaced.
     */
    fun writeRClass(rClassPath: Path) {
        val currentTable = symbolTables[0]
        // We keep the old package name for the correct package of the R class.
        val fixedTable =
            SymbolTable
                .builder()
                .tablePackage(currentTable.tablePackage)

        for (type in ResourceType.values()) {
            if (type != ResourceType.STYLEABLE) {
                // Only children under declare-styleables may need rewriting. If we're not dealing
                // with a styleable type, just add everything as-is.
                fixedTable.addAll(currentTable.getSymbolByResourceType(type))
            } else {
                // Need to fix children for declare styleables.
                namespaceStyleables(
                    currentTable.getSymbolByResourceType(ResourceType.STYLEABLE),
                    fixedTable)
            }
        }

        exportToCompiledJava(ImmutableList.of(fixedTable.build()), rClassPath)
    }

    /**
     * Namespaces all of the given styleables and places them into the given symbol table builder.
     */
    @VisibleForTesting
    fun namespaceStyleables(styleables: List<Symbol>, fixedTable: SymbolTable.Builder) {
        val currentTable = symbolTables[0]
        styleables.forEach { symbol ->
            if (symbol.children.isEmpty()) {
                // If there were no children, just add the styleable as-is.
                fixedTable.add(symbol)
            } else {
                val newChildren = ImmutableList.builder<String>()
                symbol.children.forEach {
                    if (it.contains(":")) {
                        // If the attribute is already namespaced we don't need to do anything, e.g.
                        // "android:color".
                        newChildren.add(it)
                    } else {
                        // The attribute doesn't have a package specified, find where it belongs.
                        val foundPackage = findPackageForAttr(it)
                        if (foundPackage != currentTable.tablePackage) {
                            // If it's not a local attribute, write the package.
                            newChildren.add("$foundPackage:$it")
                        } else {
                            // Local attribute, no need to include the package.
                            newChildren.add(it)
                        }
                    }
                }
                // Now add a styleable with the new children keeping the old values.
                symbol as Symbol.StyleableSymbol
                val newStyleable =
                    Symbol.createStyleableSymbol(
                        symbol.name, symbol.values, newChildren.build())
                fixedTable.add(newStyleable)
            }
        }
    }

    /**
     * Rewrites a class to be namespaced. It removes all R inner classes references from read
     * inner classes, rewrites and collects R references using the [MethodReWriter].
     * After an [ClassReader.accept] method is called using this [ClassVisitor], the method
     * [ClassReWriter.writeInnerRClasses] needs to be called to correctly fill the InnerClasses
     * attribute for the transformed class.
     */
    private inner class ClassReWriter internal constructor(
        api: Int,
        cv: ClassVisitor?
    ) : ClassVisitor(api, cv) {

        private val innerClasses = HashSet<String>()

        override fun visitInnerClass(
            name: String?, outerName: String?, innerName: String?, access: Int
        ) {
            // Do not write any original R packages for now, as they might not exist anymore
            // after resource namespacing is applied. If they still exists and are referenced in
            // this class, they will be written at the end.
            if (outerName != null && !outerName.endsWith("/R")) {
                cv.visitInnerClass(name, outerName, innerName, access)
            }
        }

        override fun visitMethod(
            access: Int,
            name: String?,
            desc: String?,
            signature: String?,
            exceptions: Array<String?>?
        ) : MethodVisitor {
            return MethodReWriter(
                api,
                cv.visitMethod(access, name, desc, signature, exceptions),
                this
            )
        }

        /**
         * References all found inner R classes. According to the JVM specification the
         * InnerClasses attribute must contain all inner classes referenced in the transformed
         * class even if they aren't member of this class.
         */
        fun writeInnerRClasses() {

            for (innerClass in innerClasses) {
                cv.visitInnerClass(
                    innerClass,
                    innerClass.substringBeforeLast('$'),
                    innerClass.substringAfterLast('$'),
                    ACC_PUBLIC + ACC_STATIC
                )
            }
        }

        /**
         * Finds the parent Symbol and its package for the given declare-styleable attribute name.
         * This will search through all SymbolTables trying to find a matching parent, and once the
         * parent is found, it will return it and the package it was found in. If the parent is not
         * found, this method will raise an error.
         */
        internal fun findStyleableChildSymbol(name: String): Pair<Symbol.StyleableSymbol, String> {
            return findStyleableChild(name)
        }

        /**
         * Finds the first package in which the R file contains a symbol with the given type and
         * name.
         */
        fun findPackageForSymbol(type: String, name: String): String {
            return findPackage(type, name)
        }

        fun addInnerClass(innerClass: String) {
            innerClasses.add(innerClass)
        }
    }

    /**
     * Rewrites field instructions to reference namespaced resources instead of the local R.
     */
    private class MethodReWriter(
        api: Int,
        mv: MethodVisitor?,
        private val crw: ClassReWriter
    ) : MethodVisitor(api, mv) {

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String?) {
            if (owner.contains("/R$")) {
                val type = owner.substringAfterLast('$')
                if (type == "styleable" && desc == "I") {
                    visitFieldInsnForStyleable(opcode, owner, name)
                } else {
                    // If it's not a styleable child, just update the R class reference.
                    val newPkg = crw.findPackageForSymbol(type, name).replace('.', '/')

                    // We need to visit the inner class later. It could happen that the [newOwner]
                    // is the same as the [owner] since a class can reference resources from its'
                    // module, but we still need to remember all references.
                    val newOwner = "$newPkg/R$$type"
                    crw.addInnerClass(newOwner)

                    this.mv.visitFieldInsn(opcode, newOwner, name, desc)
                }
            } else {
                // The field instruction does not reference an R class, visit normally.
                this.mv.visitFieldInsn(opcode, owner, name, desc)
            }
        }

        private fun visitFieldInsnForStyleable(opcode: Int, owner: String, name: String) {
            // If we found a styleable child, we not only need to update the R class
            // reference, but also need to namespace the resource name, for example:
            // "AppBarLayout_elevation" becomes "AppBarLayout_androidx_appcompat_elevation"
            // since the child attribute was defined in the AppCompat library.
            val parentPackage = crw.findStyleableChildSymbol(name)
            val parent = parentPackage.first
            val newOwner = "${parentPackage.second.replace('.', '/')}/R\$styleable"
            crw.addInnerClass(newOwner)

            // Get the parent name from the parent Symbol and remove "<parent-name>_" from
            // the original name to find the child attr name.
            val parentName = parent.canonicalName
            val childName = name.substringAfter(parentName).drop(1)

            if (childName.startsWith("android_")) {
                // Leave "android:" attributes alone since they already have the namespace, just
                // update the R class package.
                this.mv.visitFieldInsn(opcode, newOwner, name, "I")
            } else {
                // Find where the attribute was actually defined. This is the same way we resolve
                // the styleable children's packages when creating the R classes.
                val childPackage = crw.findPackageForSymbol("attr", childName)

                if (childPackage == parentPackage.second) {
                    // If the R class package matches the package where the attribute was defined,
                    // do NOT add the package to the styleable child's name as it will not be
                    // modified in the R class either.
                    this.mv.visitFieldInsn(opcode, newOwner, name, "I")
                } else {
                    // We're dealing with the most common case of the styleable children, where we
                    // are re-using an attribute from a different package than the parent (most
                    // likely the parent is in the same package as the class we're handling now).
                    // The format of the new name is "<parent-name>_<attr-package>_<child-name>",
                    // e.g. "AppBarLayout_androidx_appcompat_elevation".
                    val newName = "${parentName}_${childPackage.replace('.', '_')}_$childName"

                    this.mv.visitFieldInsn(opcode, newOwner, newName, "I")
                }
            }
        }
    }

    fun findStyleableChild(name: String): Pair<Symbol.StyleableSymbol, String> {
        val canonicalName = canonicalizeValueResourceName(name)
        for (table in symbolTables) {
            val maybeParent = table.maybeGetStyleableParentSymbolForChild(canonicalName)
            if (maybeParent != null) {
                return Pair(maybeParent, table.tablePackage)
            }
        }
        error("In package $localPackage found unknown styleable $canonicalName")
    }

    /**
     * Finds the first package in which the R file contains a symbol with the given type and
     * name.
     */
    fun findPackage(type: String, name: String): String {
        if (type == ResourceType.ATTR.getName()) {
            // Attributes are treated differently due to maybe-definitions under declare-styleables.
            return findPackageForAttr(name)
        }
        return maybeFindPackage(type = type, name = name, reversed = false)
                ?: error(
                    "In package $localPackage found unknown symbol of type " +
                            "$type and name $name."
                )
    }

    /**
     * Finds the first package in which the attribute was defined. If none match (the attribute was not
     * defined explicitly), we need to search in reverse order to find the last/furthest "maybe"
     * definition of the attribute (defined under a declare-styleable, kept in the symbol table as the
     * MAYBE_ATTR type).
     */
    private fun findPackageForAttr(name: String): String {
        var foundPackage = maybeFindPackage(type = "attr", name = name, reversed = false)
        if (foundPackage == null) {
            foundPackage = maybeFindPackage(type = "attr?", name = name, reversed = true)
        }
        return foundPackage
                ?: error("In package $localPackage found unknown attribute $name")
    }

    /**
     * Finds the first package in which the R file contains a symbol with the given type and
     * name.
     */
    private fun maybeFindPackage(type: String, name: String, reversed: Boolean): String? {
        val canonicalName = canonicalizeValueResourceName(name)
        var packages: ArrayList<String>? = null
        var result: String? = null

        // Go through R.txt files and find the proper package.
        for (table in if (reversed) symbolTables.reverse() else symbolTables) {
            if (packageContainsSymbol(table, type, canonicalName)) {
                if (result == null) {
                    result = table.tablePackage
                } else {
                    if (packages == null) {
                        packages = ArrayList()
                    }
                    packages.add(table.tablePackage)
                }
            }
        }
        if (packages != null && !packages.isEmpty()) {
            // If we have found more than one fitting package, log a warning about which one we
            // chose (the closest one in the dependencies graph).
            logger.info(
                "In package $localPackage multiple options found " +
                        "in its dependencies for resource $type $name. " +
                        "Using $result, other available: ${Joiner.on(", ").join(packages)}"
            )
        }
        // Return the first found reference.
        return result
    }

    private fun packageContainsSymbol(
        table: SymbolTable,
        type: String,
        canonicalName: String
    ): Boolean {
        val resourceType = getResourceType(type)
        if (!table.containsSymbol(resourceType, canonicalName)) {
            // If there's not matching symbol, return false
            return false
        }
        if (resourceType == ResourceType.ATTR) {
            // If we're dealing with attributes we need to check for maybe definitions.
            val attr = table.symbols.get(resourceType, canonicalName) as Symbol.AttributeSymbol
            // Package matches if we have a real attr and were looking for a real attr or if we
            // have a maybe attr and were looking for a maybe attr.
            return attr.isMaybeDefinition == (type == "attr?")
        }
        return true
    }

    /**
     * Generates a public.xml file containing public definitions for the resources from the current
     * package.
     *
     * @param publicTxt an input stream of the `public.txt` file from the AAR, or null if none present
     * @param outputDirectory the directory to output the generated file in to.
     * @return The generated file, inside the output directory
     */
    fun generatePublicFile(
        publicTxt: InputStream?,
        outputDirectory: Path
    ): Path {
        val symbols = symbolTables[0]
        val values = outputDirectory.resolve("values")
        Files.createDirectories(values)
        val publicXml = values.resolve("auto-namespace-public.xml")
        if (Files.exists(publicXml)) {
            error("Internal error: Auto namespaced public XML file already exists: " +
                    { publicXml.toAbsolutePath() })
        }

        // If the public.txt exists we will use these Symbols for creating the public.xml files (only
        // the resources specified in the public.txt will be accessible to namespaced dependencies).
        // But if the public.txt does not exist, all of the Symbols in this package will be public.
        val publicSymbols: SymbolTable =
            if (publicTxt != null)
                SymbolIo.readFromPublicTxtFile(publicTxt, "", symbols.tablePackage)
            else
                symbols

        Files.newBufferedWriter(publicXml).use {
            // If there was no public.txt file, 'symbols' and 'publicSymbols' will be the same.
            writePublicFile(it, symbols, publicSymbols)
        }
        return publicXml
    }


    @VisibleForTesting
    internal fun writePublicFile(writer: Writer, symbols: SymbolTable, publicSymbols: SymbolTable) {
        writer.write("""<?xml version="1.0" encoding="utf-8"?>""")
        writer.write("\n<resources>\n")

        // If there are no public symbols (empty public.txt or no symbols present in this lib), don't
        // waste time going through all symbols
        if (!publicSymbols.symbols.isEmpty) {
            writer.write("\n")
            // If everything is public, then there's no need to call the 'isPublic' method.
            val allPublic = symbols == publicSymbols

            // Sadly we cannot simply iterate through the public symbols table since the public.txt had
            // the resource names already canonicalized.
            symbols.resourceTypes.forEach { resourceType ->
                symbols.getSymbolByResourceType(resourceType).forEach { symbol ->
                    if (allPublic || isPublic(symbol, publicSymbols)) {
                        if (symbol.resourceType == ResourceType.ATTR) {
                            maybeWriteAttribute(symbol, writer)
                        } else {
                            writer.write("    <public name=\"")
                            writer.write(symbol.name)
                            writer.write("\" type=\"")
                            writer.write(resourceType.getName())
                            writer.write("\" />\n")
                        }
                    }
                }
            }
        }
        writer.write("\n</resources>\n")
    }

    private fun maybeWriteAttribute(symbol: Symbol, writer: Writer) {
        val foundPackage = findPackageForAttr(symbol.name)
        // Only write the "Maybe attr" to the public.txt file if the package it is resolved to is
        // the current package.
        if (foundPackage == localPackage) {
            writer.write("    <public name=\"")
            writer.write(symbol.name)
            writer.write("\" type=\"attr\" />\n")
        }
    }
}

private fun isPublic(symbol: Symbol, publicSymbols: SymbolTable): Boolean {
    return publicSymbols.containsSymbol(symbol.resourceType, symbol.canonicalName)
}

private fun getResourceType(typeString: String): ResourceType =
    if (typeString == "attr?") ResourceType.ATTR
    else ResourceType.fromClassName(typeString) ?: error("Unknown type '$typeString'")
