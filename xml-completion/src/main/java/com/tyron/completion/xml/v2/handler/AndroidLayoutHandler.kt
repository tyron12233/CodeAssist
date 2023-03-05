package com.tyron.completion.xml.v2.handler

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.StyleableResourceValue
import com.android.ide.common.resources.ResourceItem
import com.android.resources.ResourceType
import com.tyron.builder.project.api.AndroidModule
import com.tyron.completion.CompletionParameters
import com.tyron.completion.model.CompletionList
import com.tyron.completion.xml.model.XmlCompletionType
import com.tyron.completion.xml.util.StyleUtils
import com.tyron.completion.xml.util.XmlUtils
import com.tyron.completion.xml.util.XmlUtils.getCompletionType
import com.tyron.completion.xml.v2.aar.FrameworkResourceRepository
import com.tyron.completion.xml.v2.project.LocalResourceRepository
import com.tyron.completion.xml.v2.project.ResourceRepositoryManager
import com.tyron.xml.completion.util.DOMUtils
import org.eclipse.lemminx.dom.DOMElement
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

private const val UNKNOWN_TAG = "\$__UnknownTag__\$"

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
            val nodeAt = parsedNode.findNodeAt(params.index.toInt()) as DOMElement
            addLayoutAttributes(
                completionBuilder,
                frameworkResRepository,
                repositoryManager,
                nodeAt
            )
        }
        XmlCompletionType.ATTRIBUTE_VALUE -> {
            val attr = parsedNode.findAttrAt(params.index.toInt())
            val ownerElement = attr.ownerElement
            val parentView = ownerElement.parentElement
            addValueItems(
                completionBuilder,
                frameworkResRepository,
                projectResources,
                repositoryManager.namespace,
                attr,
                params
            ) {
                val classes = StyleUtils.getClasses(it)

                if (classes.isEmpty()) {
                    listOf("View", "ViewGroup", "ViewGroup_Layout")
                } else {
                    val parentLayoutParams = parentView?.let { element ->
                        val simpleName = StyleUtils.getSimpleName(element.tagName)

                        val layoutParams = StyleUtils.getClasses(simpleName)
                        if (layoutParams.isEmpty()) {
                            listOf(simpleName + "_Layout", "ViewGroup_Layout")
                        } else {
                            layoutParams.map { name -> name + "_Layout" }
                        }
                    } ?: listOf("ViewGroup_Layout")
                    classes.toList() + parentLayoutParams
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
    node: DOMElement
) {
    val ownerDocument = node.ownerDocument
    val rootElement = DOMUtils.getRootElement(ownerDocument) ?: return
    val parentView = node.parentElement

    // TODO: Suggest NS

    val simpleTagName = StyleUtils.getSimpleName(node.tagName)
    val styleNames = buildList {
        // try to search for the styleable of the tag
        add(simpleTagName)

        val currentViewClasses = StyleUtils.getClasses(simpleTagName)
        // try to search for styleable names from the parent of the view
        // e.g the parent of TextView is View, so include that as well.
        if (currentViewClasses.isEmpty()) {
            add(UNKNOWN_TAG)
        } else {
            addAll(currentViewClasses)
        }


        // now if there is a parent view, add it's layout parameters
        parentView?.apply {
            val classes = StyleUtils.getClasses(StyleUtils.getSimpleName(this.tagName))

            if (classes.isNotEmpty()) {
                // all layout params styles should end in _Layout by convention
                addAll(classes.map { name -> name + "_Layout" })
            } else {
                // this class is not found so just suggest all view attributes
                // this is based on android studio's behavior
                add(UNKNOWN_TAG)
            }
        }
    }

    var xmlnsAttributes = DOMUtils.getXmlnsAttributes(ownerDocument)
    if (xmlnsAttributes.isEmpty()) {
        xmlnsAttributes = listOf(ResourceNamespace.RES_AUTO.xmlNamespaceUri)
    }

    val items = styleNames.flatMap { styleName ->
        getStyleables(
            xmlnsAttributes,
            frameworkResRepository,
            resourceRepositoryManager.appResources
        ) {
            if (UNKNOWN_TAG == styleName) {
                it.name == "ViewGroup" || it.name.endsWith("_Layout")
                        || it.name == "View"
            } else {
                it.name == styleName
            }
        }
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

fun getStyleables(
    namespaces: List<String>,
    frameworkResRepository: FrameworkResourceRepository,
    appRepository: LocalResourceRepository,
    predicate: (ResourceItem) -> Boolean
): List<ResourceItem> {
    return (frameworkResRepository.getPublicResources(
        ResourceNamespace.ANDROID,
        ResourceType.STYLEABLE
    ) + namespaces.flatMap {
        appRepository.getResources(
            ResourceNamespace.fromNamespaceUri(it),
            ResourceType.STYLEABLE
        ).values()
    }).filter(predicate)
}