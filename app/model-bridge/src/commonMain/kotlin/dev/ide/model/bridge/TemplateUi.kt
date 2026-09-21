package dev.ide.model.bridge

import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateParameter
import dev.ide.model.template.TextValidation
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiTemplateParam

/**
 * A project template as the Create-Project gallery sees it.
 *
 * Both hosts wrote this mapping, identically and independently, because the shared home for it was a module
 * between `:project-model-api` and `:ide-ui-api` that did not exist until this one. The `when`s are total
 * over their sealed hierarchies, so a new parameter kind breaks the build here rather than silently
 * dropping out of one host's gallery.
 */
fun ProjectTemplate.toUi(): UiProjectTemplate = UiProjectTemplate(
    id = id.value,
    displayName = displayName,
    description = description,
    category = category.displayName,
    iconId = iconId,
    parameters = parameters().map { it.toUi() },
)

fun TemplateParameter.toUi(): UiTemplateParam = when (this) {
    is TemplateParameter.Text ->
        UiTemplateParam.Text(key, label, default, placeholder, validation.wireName(), help)

    is TemplateParameter.Choice -> UiTemplateParam.Choice(
        key, label, options.map { UiTemplateParam.Choice.Option(it.value, it.label) }, defaultIndex, help,
    )

    is TemplateParameter.Toggle -> UiTemplateParam.Toggle(key, label, default, help)
}

/** The validation name the UI's text field switches on. */
private fun TextValidation.wireName(): String = when (this) {
    TextValidation.NONE -> "none"
    TextValidation.IDENTIFIER -> "identifier"
    TextValidation.PACKAGE_NAME -> "package"
    TextValidation.PROJECT_NAME -> "project"
}
