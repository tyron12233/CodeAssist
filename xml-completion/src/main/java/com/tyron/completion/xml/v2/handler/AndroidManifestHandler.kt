package com.tyron.completion.xml.v2.handler

import android.text.TextUtils
import com.android.ide.common.rendering.api.AttrResourceValue
import com.android.ide.common.rendering.api.AttributeFormat.*
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType.STYLEABLE
import com.tyron.builder.project.api.AndroidModule
import com.tyron.completion.CompletionParameters
import com.tyron.completion.model.CompletionItem
import com.tyron.completion.model.CompletionList
import com.tyron.completion.model.DrawableKind
import com.tyron.completion.xml.insert.AttributeInsertHandler
import com.tyron.completion.xml.insert.ValueInsertHandler
import com.tyron.completion.xml.model.XmlCompletionType
import com.tyron.completion.xml.util.AndroidXmlTagUtils.addManifestTagItems
import com.tyron.completion.xml.util.AndroidXmlTagUtils.getManifestStyleName
import com.tyron.completion.xml.util.StyleUtils.getSimpleName
import com.tyron.completion.xml.util.XmlUtils
import com.tyron.completion.xml.v2.aar.FrameworkResourceRepository
import com.tyron.completion.xml.v2.base.BasicStyleableResourceItem
import com.tyron.xml.completion.util.DOMUtils
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.eclipse.lemminx.dom.DOMAttr
import org.eclipse.lemminx.dom.DOMNode
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager
import org.jetbrains.kotlin.utils.addToStdlib.cast

fun handleManifest(
    frameworkResRepository: FrameworkResourceRepository,
    params: CompletionParameters
): CompletionList? {
    val androidModule = params.module as AndroidModule
    val namespace = if (androidModule.namespace.isNotEmpty())
        ResourceNamespace.fromPackageName(androidModule.namespace)
    else ResourceNamespace.ANDROID
    val parsedNode = DOMParser.getInstance()
        .parse(params.contents, namespace.xmlNamespaceUri, URIResolverExtensionManager())
    val completionType = XmlUtils.getCompletionType(parsedNode, params.index)
    if (completionType == XmlCompletionType.UNKNOWN) {
        return CompletionList.EMPTY
    }

    val prefix = XmlUtils.getPrefix(parsedNode, params.index, completionType)
        ?: return CompletionList.EMPTY
    val completionBuilder = CompletionList.builder(prefix)
    when (completionType) {
        XmlCompletionType.TAG -> addManifestTagItems(prefix, completionBuilder)
        XmlCompletionType.ATTRIBUTE -> {
            addManifestAttributes(
                completionBuilder,
                frameworkResRepository,
                namespace,
                parsedNode.findNodeAt(params.index.toInt()),
                params
            )
        }
        XmlCompletionType.ATTRIBUTE_VALUE -> {
            val attr = parsedNode.findAttrAt(params.index.toInt())
            addValueItems(completionBuilder, frameworkResRepository, namespace, attr, params)
        }
        XmlCompletionType.UNKNOWN -> {}
        else -> throw IllegalArgumentException()
    }
    return completionBuilder.build()
}

private fun addValueItems(
    completionBuilder: CompletionList.Builder?,
    frameworkResRepository: FrameworkResourceRepository,
    namespace: ResourceNamespace?,
    attr: DOMAttr?,
    params: CompletionParameters
) {
    if (attr == null) {
        return
    }

    val ownerElement = attr.ownerElement ?: return
    val tagName = ownerElement.tagName ?: return
    val manifestStyleName = getManifestStyleName(tagName) ?: return

    val result = frameworkResRepository.getResources(ResourceNamespace.ANDROID, STYLEABLE, manifestStyleName)
    if (result.isEmpty()) {
        return
    }

    val styleableResource = result.first().cast<BasicStyleableResourceItem>()
    styleableResource.allAttributes.filter {
        FuzzySearch.partialRatio(it.name, attr.localName) >= 70
    }.flatMap { attribute ->
        if (attribute.formats.contains(ENUM) || attribute.formats.contains(FLAGS)) {
            return@flatMap attribute.attributeValues.keys.map { flag ->
                CompletionItem.create(flag, "Value", flag, DrawableKind.Snippet).apply {
                    setInsertHandler(ValueInsertHandler(attribute, this))
                    addFilterText(flag)
                }
            }
        }
        if (attribute.formats.contains(BOOLEAN)) {
            return@flatMap listOf("true", "false").map {
                CompletionItem.create(it, "Value", it, DrawableKind.Snippet).apply {
                    setInsertHandler(ValueInsertHandler(attribute, this))
                    addFilterText(it)
                }
            }
        }
        emptyList()
    }.forEach {
        completionBuilder?.addItem(it)
    }
}

private fun addManifestAttributes(
    completionBuilder: CompletionList.Builder,
    frameworkResRepository: FrameworkResourceRepository,
    namespace: ResourceNamespace,
    parsedNode: DOMNode,
    parameters: CompletionParameters
) {
    val tagName = getSimpleName(parsedNode.nodeName)
    val styleName = getManifestStyleName(tagName) ?: tagName

    // find the style resource in the repository
    val result = frameworkResRepository.getResources(ResourceNamespace.ANDROID, STYLEABLE, styleName)
    if (result.isEmpty()) {
        return
    }
    val styleableResourceItem = result.first() as BasicStyleableResourceItem
    addAttributes(
        styleableResourceItem.allAttributes,
        parsedNode,
        completionBuilder
    )
}

private fun addAttributes(
    tagAttributes: List<AttrResourceValue>,
    node: DOMNode,
    builder: CompletionList.Builder
) {
    val resolver = DOMUtils.getNamespaceResolver(node.ownerDocument)
    val uniques: MutableSet<String> = HashSet()
    for (tagAttribute in tagAttributes) {
        val name = tagAttribute.name
        val reference: ResourceReference = if (name.contains(":")) {
            val prefix = name.substring(0, name.indexOf(':'))
            val fixedName = name.substring(name.indexOf(':') + 1)
            val namespace = ResourceNamespace.fromNamespacePrefix(
                prefix,
                tagAttribute.namespace,
                tagAttribute.namespaceResolver
            )
            ResourceReference(namespace!!, tagAttribute.resourceType, fixedName)
        } else {
            tagAttribute.asReference()
        }
        var prefix = resolver.uriToPrefix(
            reference.namespace
                .xmlNamespaceUri
        )
        if (TextUtils.isEmpty(prefix)) {
            if (tagAttribute.libraryName != null) {
                // default to res-auto namespace, commonly prefixed as 'app'
                prefix = resolver.uriToPrefix(ResourceNamespace.RES_AUTO.xmlNamespaceUri)
            }
        }
        val commitText = when {
            TextUtils.isEmpty(prefix) -> reference.name
            else ->   "$prefix:${reference.name}"
        }
        if (uniques.contains(commitText)) {
            continue
        }
        val attribute = CompletionItem.create(
            commitText, "Attribute", commitText,
            DrawableKind.Attribute
        )
        attribute.addFilterText(commitText)
        attribute.addFilterText(reference.name)
        attribute.setInsertHandler(AttributeInsertHandler(attribute))
        builder.addItem(attribute)
        uniques.add(commitText)
    }
}