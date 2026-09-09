package dev.ide.core

/**
 * The engine-side model behind the real-view layout attribute editor (mapped onto neutral UI DTOs by the
 * preview backend). [LayoutElementInfo] is the element the user tapped; [LayoutAttrInfo] is one attribute,
 * carrying the schema shape (boolean / enum / flags / resource types) that drives the UI's typed value control.
 */
class LayoutElementInfo(
    /** The element's tag as written in the layout (`TextView`, `com.google.android.material.button.MaterialButton`). */
    val tag: String,
    /** The element's `@+id/…`/`@id/…` entry name, or null when it has none. */
    val id: String?,
    /** Start offset of the `<Tag …>` in the live buffer (the anchor every edit re-derives against). */
    val sourceOffset: Int,
    /** Attributes currently set on the element, in source order (namespace declarations excluded). */
    val setAttributes: List<LayoutAttrInfo>,
    /** Attributes valid on this element but not yet set — the "only allowed attributes" add list. */
    val addable: List<LayoutAttrInfo>,
)

/**
 * One attribute for the editor: [name] is the attribute as written (prefixed — `android:text`, `app:srcCompat`,
 * or an unprefixed `layout_width`), [value] is the current value (null for an addable-but-unset attribute), and
 * the remaining fields describe what values are accepted so the UI can pick a control (chips / switch / dp field
 * / color / reference) and still fall back to the completion-backed text field.
 */
class LayoutAttrInfo(
    val name: String,
    val value: String?,
    val boolean: Boolean,
    val enumValues: List<String>,
    val flagValues: List<String>,
    /** Accepted resource-reference types as R-class names (`color`, `drawable`, `string`, `id`, …). */
    val resourceRClasses: List<String>,
)
