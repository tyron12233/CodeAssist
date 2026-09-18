package dev.ide.ios

import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.highlight.SemanticToken
import dev.ide.lang.hints.InlayHint
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.NavKind
import dev.ide.lang.kotlin.NavTarget
import dev.ide.lang.resolve.QuickDocInfo
import dev.ide.lang.signature.SignatureHelp
import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiNavKind
import dev.ide.ui.backend.UiNavTarget
import dev.ide.ui.backend.UiQuickDoc
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.backend.UiSignature
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.backend.UiSignatureParam
import dev.ide.ui.backend.UiTextEdit

/**
 * The editor engine's pass results as the UI contract spells them.
 *
 * The companion of `IosCompletionMapping`, and duplicated from `:ide-core`'s `EditorBackend` for the same
 * reason that one is: the mapping lives in the JVM host, this module cannot see it, and the shared home for
 * it would be a bridge module between `:language-api` and `:ide-ui-api` that does not exist yet. Each
 * mapping is total over its enum, so a new kind on either side is a compile error here rather than a
 * silently dropped hint.
 */
internal fun InlayHint.toUi(): UiInlayHint = UiInlayHint(
    offset = offset,
    // A part's `symbol` is dropped: it exists to make a rendered type name click-to-navigate, and
    // go-to-definition is not wired on this host yet. The text renders the same either way.
    parts = parts.map { UiInlayPart(it.text) },
    kind = when (kind) {
        InlayHintKind.TYPE -> UiInlayKind.Type
        InlayHintKind.PARAMETER -> UiInlayKind.Parameter
        InlayHintKind.CHAINING -> UiInlayKind.Chaining
        InlayHintKind.OTHER -> UiInlayKind.Other
    },
    tooltip = tooltip,
    paddingLeft = paddingLeft,
    paddingRight = paddingRight,
)

internal fun SignatureHelp.toUi(): UiSignatureHelp = UiSignatureHelp(
    signatures = signatures.map { s ->
        UiSignature(
            label = s.label,
            parameters = s.parameters.map { UiSignatureParam(it.label, it.labelStart, it.labelEnd, it.alreadyNamed) },
            documentation = s.documentation,
            activeParameter = s.activeParameter,
        )
    },
    activeSignature = activeSignature,
    activeParameter = activeParameter,
)

/**
 * An engine edit as the UI applies it.
 *
 * The two spell a replacement differently and the conversion is the whole point: the engine carries an offset
 * plus the length of what it replaces, the UI a `[start, end)` range.
 */
internal fun SemanticToken.toUi(): UiSemanticToken =
    UiSemanticToken(range.start, range.end, kind.id, modifiers.mapTo(LinkedHashSet()) { it.toUi() })

/** Exhaustive on purpose: a new modifier should fail to compile here rather than silently lose its styling. */
internal fun HighlightModifier.toUi(): UiHighlightModifier = when (this) {
    HighlightModifier.DECLARATION -> UiHighlightModifier.Declaration
    HighlightModifier.STATIC -> UiHighlightModifier.Static
    HighlightModifier.ABSTRACT -> UiHighlightModifier.Abstract
    HighlightModifier.DEPRECATED -> UiHighlightModifier.Deprecated
    HighlightModifier.READONLY -> UiHighlightModifier.Readonly
    HighlightModifier.MUTABLE -> UiHighlightModifier.Mutable
    HighlightModifier.EXTENSION -> UiHighlightModifier.Extension
    HighlightModifier.COMPOSABLE -> UiHighlightModifier.Composable
    HighlightModifier.SUSPEND -> UiHighlightModifier.Suspend
}

internal fun DocumentEdit.toUi(): UiTextEdit = UiTextEdit(offset, offset + oldLength, newText.toString())

/** A navigation destination as the UI names it. */
internal fun NavTarget.toUi(): UiNavTarget = UiNavTarget(file.path, offset, label, kind)

internal fun NavKind.toUi(): UiNavKind = when (this) {
    NavKind.DECLARATION -> UiNavKind.DECLARATION
    NavKind.IMPLEMENTATION -> UiNavKind.IMPLEMENTATION
    NavKind.TYPE_DECLARATION -> UiNavKind.TYPE_DECLARATION
    NavKind.SUPER -> UiNavKind.SUPER
}

internal fun UiNavKind.toEngine(): NavKind = when (this) {
    UiNavKind.DECLARATION -> NavKind.DECLARATION
    UiNavKind.IMPLEMENTATION -> NavKind.IMPLEMENTATION
    UiNavKind.TYPE_DECLARATION -> NavKind.TYPE_DECLARATION
    UiNavKind.SUPER -> NavKind.SUPER
}

internal fun QuickDocInfo.toUi(): UiQuickDoc = UiQuickDoc(
    signature = signature,
    name = name,
    kind = kind.name.lowercase(),
    container = container,
    doc = doc,
    docFormat = docFormat.name.lowercase(),
)
