package com.tyron.completion.xml.v2.handler

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceType.STYLEABLE
import com.tyron.builder.project.api.AndroidModule
import com.tyron.completion.CompletionParameters
import com.tyron.completion.model.CompletionList
import com.tyron.completion.xml.model.XmlCompletionType
import com.tyron.completion.xml.util.AndroidXmlTagUtils.addManifestTagItems
import com.tyron.completion.xml.util.AndroidXmlTagUtils.getManifestStyleName
import com.tyron.completion.xml.util.StyleUtils.getSimpleName
import com.tyron.completion.xml.util.XmlUtils
import com.tyron.completion.xml.v2.aar.FrameworkResourceRepository
import com.tyron.completion.xml.v2.base.BasicStyleableResourceItem
import com.tyron.completion.xml.v2.project.ResourceRepositoryManager
import org.eclipse.lemminx.dom.DOMNode
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

fun handleManifest(
    frameworkResRepository: FrameworkResourceRepository,
    params: CompletionParameters
): CompletionList? {
    val androidModule = params.module as AndroidModule
    val repositoryManager = ResourceRepositoryManager.getInstance(androidModule)
    val parsedNode = DOMParser.getInstance()
        .parse(
            params.contents,
            repositoryManager.namespace.xmlNamespaceUri,
            URIResolverExtensionManager()
        )
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
                repositoryManager.namespace,
                parsedNode.findNodeAt(params.index.toInt()),
                params
            )
        }
        XmlCompletionType.ATTRIBUTE_VALUE -> {
            val attr = parsedNode.findAttrAt(params.index.toInt())
            addValueItems(
                completionBuilder,
                frameworkResRepository,
                repositoryManager.appResources,
                repositoryManager.namespace,
                attr,
                params
            ) {
                listOf(getManifestStyleName(it) ?: it)
            }
        }
        XmlCompletionType.UNKNOWN -> {}
        else -> throw IllegalArgumentException()
    }
    return completionBuilder.build()
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
    val result =
        frameworkResRepository.getResources(ResourceNamespace.ANDROID, STYLEABLE, styleName)
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