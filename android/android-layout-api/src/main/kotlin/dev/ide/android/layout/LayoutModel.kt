package dev.ide.android.layout

import dev.ide.platform.ServiceKey

/**
 * The IDE's understanding of Android layout XML, for a plugin that edits layouts structurally.
 *
 * Everything here works on a buffer the caller passes in, not on the file on disk, so a design surface can ask
 * about exactly what the code editor holds. Edits come back as [LayoutEdit]s against that buffer rather than
 * being applied, so the caller applies them through the editor (one undo step, the code view in sync).
 *
 * The parser is the editor's own tolerant one: a half-typed buffer still parses, with the broken element
 * dropped rather than the whole tree. The attribute knowledge is the same SDK metadata, library `attrs.xml`
 * and project `declare-styleable`s the XML editor completes from, so a design surface offers exactly the
 * attributes typing would.
 *
 * Elements are addressed by [ElementRef]: the offset of their `<Tag`, plus their id when they have one. The id
 * is what keeps a reference valid after an edit elsewhere has shifted offsets.
 */
interface AndroidLayoutModel {

    /** Parse [text] into the element tree. Cheap enough to call per keystroke on a typical layout. */
    fun parse(text: String): LayoutDocument

    /**
     * The attributes the element [tag] may carry inside a parent [parentTag] (null for the root), set or not:
     * its own and inherited view attributes plus the `layout_*` parameters its parent accepts, for the module
     * [path] belongs to. Each is named as it is written (`android:text`, `app:layout_constraintTop_toTopOf`).
     */
    suspend fun attributes(path: String, tag: String, parentTag: String?): List<LayoutAttributeInfo>

    /**
     * Value completion for [attribute] on [element], as if [fieldText] (caret at [caret]) were typed as its
     * value: the candidates the XML editor would show there. The replacement range is relative to [fieldText].
     */
    suspend fun completeValue(
        path: String,
        text: String,
        element: ElementRef,
        attribute: String,
        fieldText: String,
        caret: Int,
    ): LayoutValueCompletion

    /**
     * Edits that set [attribute] to [value] on [element]: the value replaced when the attribute is present,
     * else the attribute inserted in the element's own style (its own line when the element writes one
     * attribute per line) with its namespace declared on the root if it was missing. Empty when nothing changes
     * or the element is not found.
     */
    suspend fun setAttribute(path: String, text: String, element: ElementRef, attribute: String, value: String): List<LayoutEdit>

    /** Edits that remove [attribute] from [element]. Empty when it is not set. */
    suspend fun removeAttribute(path: String, text: String, element: ElementRef, attribute: String): List<LayoutEdit>
}

/** The project's layout model. See [AndroidLayoutModel]. */
val ANDROID_LAYOUT_MODEL: ServiceKey<AndroidLayoutModel> = ServiceKey("android.layoutModel")

/** An element of a layout buffer: the offset of its `<Tag`, and its id entry name when it has one. */
data class ElementRef(val offset: Int, val id: String? = null)

/** Replace `[start, end)` of the buffer with [newText]. */
data class LayoutEdit(val start: Int, val end: Int, val newText: String)

/** A parsed layout buffer. [root] is null for a buffer with no element yet. */
class LayoutDocument(val text: String, val root: LayoutElement?) {

    /** Every element, parents first. */
    fun elements(): Sequence<LayoutElement> = root?.walk() ?: emptySequence()

    /** The innermost element whose range contains [offset]. */
    fun elementAt(offset: Int): LayoutElement? {
        var found: LayoutElement? = null
        var node = root
        while (node != null && offset >= node.start && offset <= node.end) {
            found = node
            node = node.children.firstOrNull { offset >= it.start && offset <= it.end }
        }
        return found
    }

    /** The element [ref] names: by id when it has one and the id is still in the buffer, else by offset. */
    fun find(ref: ElementRef): LayoutElement? {
        if (ref.id != null) elements().firstOrNull { it.id == ref.id }?.let { return it }
        return elements().firstOrNull { it.start == ref.offset } ?: elementAt(ref.offset)
    }
}

/**
 * One element: its [tag] as written, its whole range `[start, end)`, and where its start tag ends
 * ([startTagEnd], just past the `>` or `/>`). [attributes] are in source order, namespace declarations
 * included. [children] are its child elements (text and comments are not elements).
 */
class LayoutElement(
    val tag: String,
    val start: Int,
    val end: Int,
    val startTagEnd: Int,
    val selfClosing: Boolean,
    val attributes: List<LayoutAttribute>,
    val children: List<LayoutElement>,
) {
    /** The parent element; null for the root. Set when the tree is assembled. */
    var parent: LayoutElement? = null
        private set

    init {
        for (c in children) c.parent = this
    }

    /** The class's simple name, for display (`MaterialButton` for its fully-qualified tag). */
    val simpleName: String get() = tag.substringAfterLast('.')

    /** The `android:id` entry name (`submit` for `@+id/submit`), or null. */
    val id: String? get() = attribute("android:id")?.value?.substringAfterLast('/')?.ifEmpty { null }

    val ref: ElementRef get() = ElementRef(start, id)

    fun attribute(name: String): LayoutAttribute? = attributes.firstOrNull { it.name == name }

    /** This element and every descendant, parents first. */
    fun walk(): Sequence<LayoutElement> = sequence {
        yield(this@LayoutElement)
        for (c in children) yieldAll(c.walk())
    }

    /** Depth below the root (the root is 0). */
    val depth: Int get() = generateSequence(parent) { it.parent }.count()
}

/** One attribute: [name] and [value] as written (entities not decoded), its whole range and its value's range (inside the quotes). */
class LayoutAttribute(
    val name: String,
    val value: String,
    val start: Int,
    val end: Int,
    val valueStart: Int,
    val valueEnd: Int,
)

/**
 * What an attribute accepts. [formats] are the `attrs.xml` formats (`dimension`, `color`, `reference`, `enum`,
 * `flags`, `boolean`, `string`, `integer`, `float`, `fraction`). [enumValues]/[flagValues] are the keywords an
 * enum or flags attribute takes (a dimension like `layout_width` lists `match_parent`/`wrap_content` here too).
 * [resourceTypes] are the `@type/` references it accepts (`string`, `color`, `drawable`, ...).
 */
class LayoutAttributeInfo(
    val name: String,
    val formats: Set<String>,
    val enumValues: List<String> = emptyList(),
    val flagValues: List<String> = emptyList(),
    val resourceTypes: List<String> = emptyList(),
    /** The attribute's documentation, from the SDK or the library's `attrs.xml`, when there is any. */
    val doc: String? = null,
)

/** Value candidates; replace `[replaceStart, replaceEnd)` of the field with a candidate's [LayoutValueCandidate.insertText]. */
class LayoutValueCompletion(
    val candidates: List<LayoutValueCandidate>,
    val replaceStart: Int,
    val replaceEnd: Int,
)

/** One value candidate: what to show ([label], [detail]) and what to insert. [kind] is a lowercase hint (`color`, `string`, `keyword`). */
class LayoutValueCandidate(
    val label: String,
    val insertText: String,
    val detail: String? = null,
    val kind: String? = null,
)
