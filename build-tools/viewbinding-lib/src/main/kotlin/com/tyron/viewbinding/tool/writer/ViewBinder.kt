package com.tyron.viewbinding.tool.writer

import com.tyron.viewbinding.tool.ext.N
import com.tyron.viewbinding.tool.ext.T
import com.tyron.viewbinding.tool.ext.XmlResourceReference
import com.tyron.viewbinding.tool.ext.parseLayoutClassName
import com.tyron.viewbinding.tool.ext.parseXmlResourceReference
import com.tyron.viewbinding.tool.store.ResourceBundle.BindingTargetBundle
import com.tyron.viewbinding.tool.writer.ViewBinder.RootNode
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock

/** The model for a view binder which corresponds to a single layout and its contained views. */
data class ViewBinder(
    val generatedTypeName: ClassName,
    val layoutReference: ResourceReference,
    val bindings: List<ViewBinding>,
    val rootNode: RootNode
) {
    init {
        require(layoutReference.type == "layout") {
            "Layout reference type must be 'layout': $layoutReference"
        }
        if (rootNode is RootNode.Binding) {
            require(rootNode.binding in bindings) {
                "Root node binding is not present in bindings list: ${rootNode.binding}, $bindings"
            }
            require(rootNode.binding.isRequired) {
                "Root node binding is not present in all configurations: ${rootNode.binding}"
            }
        }
    }

    /** Describes the root node of the layout. */
    sealed class RootNode {
        /** Root `<merge>` tag. */
        object Merge : RootNode()
        /** Root view of type [type] with no ID or with IDs that vary across configurations. */
        data class View(val type: ClassName): RootNode()
        /** Root view is the same as that for [binding]. */
        data class Binding(val binding: ViewBinding): RootNode() {
            init {
                require(binding.isRequired) { "Root bindings cannot be optional" }
            }
        }
    }
}

/** The model for a view binding which corresponds to a single view inside of a layout. */
data class ViewBinding(
    val name: String,
    val type: ClassName,
    val form: Form,
    val id: ResourceReference,
    /** Layout folders that this view is present in. */
    val presentConfigurations: List<String>,
    /**
     * Layout folders that this view is absent from. A non-empty list indicates this view binding
     * is optional!
     *
     * @see isRequired
     */
    val absentConfigurations: List<String>
) {
    init {
        require(id.type == "id") { "ID reference type must be 'id': $id" }
    }

    val isRequired get() = absentConfigurations.isEmpty()

    enum class Form {
        View, Binder
        // TODO ViewStub
    }
}

data class ResourceReference(val rClassName: ClassName, val type: String, val name: String) {
    fun asCode(): CodeBlock = CodeBlock.of("$T.$N", rClassName.nestedClass(type), name)
}

fun BaseLayoutModel.toViewBinder(): ViewBinder {
    val rClassName = ClassName.get(modulePackage, "R")

    fun BindingTargetBundle.toBinding(): ViewBinding {
        val idReference = id.parseXmlResourceReference().toResourceReference(rClassName)
        val (present, absent) = layoutConfigurationMembership(this)

        return ViewBinding(
            name = fieldName(this),
            type = parseLayoutClassName(fieldType, baseFileName),
            form = if (isBinder) ViewBinding.Form.Binder else ViewBinding.Form.View,
            id = idReference,
            presentConfigurations = present,
            absentConfigurations = absent
        )
    }

    val bindings = sortedTargets
        .filter { it.id != null }
        .filter { it.viewName != "merge" } // <merge> can have ID but it's ignored at runtime.
        .map { it.toBinding() }
    val rootNode = parseRootNode(rClassName, bindings)
    return ViewBinder(
        generatedTypeName = ClassName.get(bindingClassPackage, bindingClassName),
        layoutReference = ResourceReference(rClassName, "layout", baseFileName),
        bindings = bindings,
        rootNode = rootNode
    )
}

private fun BaseLayoutModel.parseRootNode(
    rClassName: ClassName,
    bindings: List<ViewBinding>
): RootNode {
    if (variations.any { it.isMerge }) {
        // If anyone is a <merge>, everyone must be a <merge>.
        check(variations.all { it.isMerge }) {
            val (present, absent) = variations.partition { it.isMerge }
            """|Configurations for $baseFileName.xml must agree on the use of a root <merge> tag.
               |
               |Present:
               |${present.joinToString("\n|") { " - ${it.directory}" }}
               |
               |Absent:
               |${absent.joinToString("\n|") { " - ${it.directory}" }}
               """.trimMargin()
        }
        return RootNode.Merge
    }

    if (variations.any { it.rootNodeViewId != null }) {
        // If anyone has a root ID, everyone must agree on it.
        val uniqueIds = variations.mapTo(HashSet()) { it.rootNodeViewId }
        check(uniqueIds.size == 1) {
            buildString {
                append("Configurations for $baseFileName.xml must agree on the root element's ID.")
                uniqueIds.sortedWith(nullsFirst(naturalOrder())).forEach { id ->
                    append("\n\n${id ?: "Missing ID"}:\n")
                    val matching = variations.filter { it.rootNodeViewId == id }
                    append(matching.joinToString("\n") { " - ${it.directory}" })
                }
            }
        }
        // All variation's root nodes agree on the ID.
        val idName = uniqueIds.single()!!
        val id = idName.parseXmlResourceReference().toResourceReference(rClassName)

        // Check to make sure that the ID matches a binding. Ignored tags like <merge> or <fragment>
        // might have an ID but not have an actual binding. Only use ID if a match was found.
        val rootBinding = bindings.singleOrNull { it.id == id }
        if (rootBinding != null) {
            return RootNode.Binding(rootBinding)
        }
    }

    val rootViewType = variations
        // Create a set of root node view types for all variations.
        .mapTo(LinkedHashSet()) { parseLayoutClassName(it.rootNodeViewType, baseFileName) }
        // If all of the variations agree on the type, use it.
        .singleOrNull()
    // Otherwise fall back to View.
        ?: ANDROID_VIEW
    return RootNode.View(rootViewType)
}

private fun XmlResourceReference.toResourceReference(moduleRClass: ClassName): ResourceReference {
    val rClassName = when (namespace) {
        "android" -> ANDROID_R
        null -> moduleRClass
        else -> throw IllegalArgumentException("Unknown namespace: $this")
    }
    return ResourceReference(rClassName, type, name)
}

private val ANDROID_R = ClassName.get("android", "R")