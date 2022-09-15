package com.tyron.completion.xml.v2.handler

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.StyleableResourceValue
import com.android.resources.ResourceType
import com.tyron.builder.project.api.AndroidModule
import com.tyron.completion.CompletionParameters
import com.tyron.completion.model.CompletionList
import com.tyron.completion.xml.model.XmlCompletionType
import com.tyron.completion.xml.util.StyleUtils
import com.tyron.completion.xml.util.XmlUtils
import com.tyron.completion.xml.util.XmlUtils.getCompletionType
import com.tyron.completion.xml.v2.aar.FrameworkResourceRepository
import com.tyron.completion.xml.v2.project.ResourceRepositoryManager
import com.tyron.xml.completion.util.DOMUtils
import org.eclipse.lemminx.dom.DOMNode
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

fun handleLayout(
    frameworkResRepository: FrameworkResourceRepository,
    params: CompletionParameters
): CompletionList? {
    val repositoryManager = ResourceRepositoryManager.getInstance(params.module as AndroidModule)
    val projectResources = repositoryManager.appResources
    val parsedNode = DOMParser.getInstance().parse(
        params.contents,
        repositoryManager.namespace.xmlNamespaceUri,
        URIResolverExtensionManager()
    )
    val completionType = getCompletionType(parsedNode, params.index)
    if (completionType == XmlCompletionType.UNKNOWN) {
        return CompletionList.EMPTY
    }

    val prefix = XmlUtils.getPrefix(parsedNode, params.index, completionType)
        ?: return CompletionList.EMPTY
    val completionBuilder = CompletionList.builder(prefix)

    when (completionType) {
        XmlCompletionType.TAG -> {

        }
        XmlCompletionType.ATTRIBUTE -> {
            val nodeAt = parsedNode.findNodeAt(params.index.toInt())
            addLayoutAttributes(
                completionBuilder,
                frameworkResRepository,
                repositoryManager,
                nodeAt
            )
        }
        XmlCompletionType.ATTRIBUTE_VALUE -> {
            val attr = parsedNode.findAttrAt(params.index.toInt())
            addValueItems(completionBuilder, frameworkResRepository, projectResources, repositoryManager.namespace, attr, params) {
                val classes = StyleUtils.getClasses(it)
                if (classes.isEmpty()) {
                    listOf("View", "ViewGroup", "ViewGroup_Layout")
                } else {
                    classes.toList() + "ViewGroup_Layout"
                }
            }
        }
        XmlCompletionType.UNKNOWN -> {

        }
        else -> throw IllegalArgumentException()
    }

    return completionBuilder.build()
}

fun addLayoutAttributes(
    builder: CompletionList.Builder,
    frameworkResRepository: FrameworkResourceRepository,
    resourceRepositoryManager: ResourceRepositoryManager,
    node: DOMNode
) {
    val ownerDocument = node.ownerDocument
    val rootElement = DOMUtils.getRootElement(ownerDocument) ?: return

    // TODO: Suggest NS

    val styleNames = buildList {
        // try to search for the styleable of the tag
        add(node.nodeName)

        // try to search for styleable names from the parent of the view
        // e.g the parent of TextView is View, so include that as well.
        addAll(StyleUtils.getClasses(node.nodeName))
    }

    val items = styleNames.flatMap {
        frameworkResRepository.getPublicResources(
            ResourceNamespace.ANDROID,
            ResourceType.STYLEABLE
        ).filter { item ->
            item.name == it
        } + resourceRepositoryManager.projectResources.getResources(
            resourceRepositoryManager.namespace,
            ResourceType.STYLEABLE,
            it
        )
    }.filter {
        it is StyleableResourceValue
    }.map {
        it as StyleableResourceValue
    }.flatMap {
        it.allAttributes
    }.filter {
        !it.name.startsWith("__removed")
                || (it.description != null && !it.description.contains("@hide"))
    }


    addAttributes(items, node, builder)
}