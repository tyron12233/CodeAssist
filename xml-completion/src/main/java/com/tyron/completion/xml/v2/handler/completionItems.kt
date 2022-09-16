package com.tyron.completion.xml.v2.handler

import android.text.TextUtils
import com.android.ide.common.rendering.api.*
import com.android.ide.common.resources.ResourceRepository
import com.android.ide.common.resources.ResourceResolver
import com.android.ide.common.resources.ResourceValueMap
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.resources.getConfiguredResources
import com.android.resources.ResourceType
import com.tyron.completion.CompletionParameters
import com.tyron.completion.model.CompletionItem
import com.tyron.completion.model.CompletionList
import com.tyron.completion.model.DrawableKind
import com.tyron.completion.xml.insert.AttributeInsertHandler
import com.tyron.completion.xml.insert.ValueInsertHandler
import com.tyron.completion.xml.v2.aar.FrameworkResourceRepository
import com.tyron.completion.xml.v2.project.LocalResourceRepository
import com.tyron.xml.completion.util.DOMUtils
import org.eclipse.lemminx.dom.DOMAttr
import org.eclipse.lemminx.dom.DOMNode

fun addAttributes(
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
            else -> "$prefix:${reference.name}"
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

fun addValueItems(
    completionBuilder: CompletionList.Builder?,
    frameworkResRepository: FrameworkResourceRepository,
    projectResources: LocalResourceRepository?,
    namespace: ResourceNamespace,
    attr: DOMAttr?,
    params: CompletionParameters,
    styleTransformer: (String) -> List<String>?
) {
    if (attr == null) {
        return
    }
    val ownerElement = attr.ownerElement ?: return
    val parentAttributeNames = ownerElement.attributeNodes.map(DOMUtils::getNameWithoutPrefix)
    val tagName = ownerElement.tagName ?: return
    val styleNames = styleTransformer(tagName) ?: return


    // first look for android resources
    val foundStyles = styleNames.flatMap {
        frameworkResRepository.getResources(
            ResourceNamespace.ANDROID,
            ResourceType.STYLEABLE,
            it
        )
    } + styleNames.flatMap {
        projectResources?.getResources(
            namespace,
            ResourceType.STYLEABLE,
            it
        ) ?: listOf()
    }


    // resource not found
    if (foundStyles.isEmpty()) {
        return
    }

    val resourceResolver = ResourceResolver.create(
        frameworkResRepository.getConfiguredResources(
            FolderConfiguration.createDefault()
        ).rowMap(),
        null
    )

    foundStyles.filterIsInstance<StyleableResourceValue>().flatMap {
        it.allAttributes
    }.filter {
        it.name.equals(attr.localName)
    }.map {
        // android styleable attributes can declare empty formats which means
        // we should search for it in its parent
        if (it.formats.isEmpty()) {
            resourceResolver.getResolvedResource(it.asReference()) as? AttrResourceValue ?: it
        } else it
    }.flatMap { attribute ->
        return@flatMap attribute.formats.flatMap { format ->
            when (format) {
                AttributeFormat.ENUM, AttributeFormat.FLAGS -> {
                    attribute.attributeValues.keys.map { flag ->
                        CompletionItem.create(flag, "Value", flag, DrawableKind.Snippet).apply {
                            setInsertHandler(ValueInsertHandler(attribute, this))
                            addFilterText(flag)
                        }
                    }
                }
                AttributeFormat.BOOLEAN -> {
                    createBooleanDefaultValues(attribute) + getItems(
                        attribute,
                        frameworkResRepository,
                        projectResources, namespace, ResourceType.BOOL
                    )
                }
                AttributeFormat.STRING -> {
                    getItems(
                        attribute,
                        frameworkResRepository,
                        projectResources,
                        namespace,
                        ResourceType.STRING
                    )
                }
                AttributeFormat.DIMENSION -> {
                    getItems(
                        attribute,
                        frameworkResRepository,
                        projectResources,
                        namespace,
                        ResourceType.DIMEN
                    )
                }
                AttributeFormat.COLOR -> {
                    getItems(
                        attribute,
                        frameworkResRepository,
                        projectResources,
                        namespace,
                        ResourceType.COLOR
                    )
                }
                AttributeFormat.FRACTION -> {
                    getItems(
                        attribute,
                        frameworkResRepository,
                        projectResources,
                        namespace,
                        ResourceType.FRACTION
                    )
                }
                AttributeFormat.INTEGER -> {
                    getItems(
                        attribute,
                        frameworkResRepository,
                        projectResources,
                        namespace,
                        ResourceType.INTEGER
                    )
                }
                AttributeFormat.REFERENCE -> {
                    val resourceValues = ResourceType.REFERENCEABLE_TYPES.flatMap { resourceType ->
                        getConfiguredResourceTypes(
                            frameworkResRepository,
                            projectResources,
                            resourceType
                        ).values
                    }
                    getReferenceItems(namespace, attribute, resourceValues)
                }
                else -> {
                    emptyList()
                }
            }
        }
    }.forEach {
        completionBuilder?.addItem(it)
    }
}

fun getConfiguredResourceTypes(
    frameworkResRepository: FrameworkResourceRepository,
    projectResources: LocalResourceRepository?,
    resourceType: ResourceType
): ResourceValueMap {
    val resourceValueMap = frameworkResRepository.getConfiguredPublicResources(
        ResourceNamespace.ANDROID,
        resourceType,
        FolderConfiguration.createDefault()
    )

    projectResources?.apply {
        resourceValueMap.putAll(
            projectResources.getConfiguredResources(
                ResourceNamespace.RES_AUTO,
                resourceType,
                FolderConfiguration.createDefault()
            )
        )
    }

    return resourceValueMap
}

fun getItems(
    attribute: AttrResourceValue,
    frameworkResRepository: FrameworkResourceRepository,
    projectResources: LocalResourceRepository?,
    namespace: ResourceNamespace,
    resourceType: ResourceType
): List<CompletionItem> {
    val resourceValueMap =
        getConfiguredResourceTypes(frameworkResRepository, projectResources, resourceType)
    return getReferenceItems(namespace, attribute, resourceValueMap.values)
}

fun getReferenceItems(
    namespace: ResourceNamespace,
    attribute: AttrResourceValue,
    items: Collection<ResourceValue>
): List<CompletionItem> {
    return items.map {
        val selfReference = it.asReference().getRelativeResourceUrl(namespace)
        CompletionItem.create(
            selfReference.name,
            "Reference",
            selfReference.toString(),
            DrawableKind.Attribute
        ).apply {
            setInsertHandler(ValueInsertHandler(selfReference, attribute, this))
            addFilterText(selfReference.name)
            addFilterText(selfReference.toString())
        }
    }
}

fun createBooleanDefaultValues(attribute: AttrResourceValue): List<CompletionItem> {
    return listOf("true", "false").map {
        CompletionItem.create(it, "Value", it, DrawableKind.Snippet).apply {
            setInsertHandler(ValueInsertHandler(attribute, this))
            addFilterText(it)
        }
    }
}

fun ResourceRepository.getConfiguredPublicResources(
    namespace: ResourceNamespace,
    type: ResourceType,
    referenceConfig: FolderConfiguration
): ResourceValueMap {
    val itemsByName = getPublicResources(namespace, type)
    val result = ResourceValueMap.createWithExpectedSize(itemsByName.size)

    for (resourceItem in itemsByName) {
        val value = resourceItem.resourceValue
        if (value != null) {
            result[resourceItem.name] = value
        }
    }

    return result
}