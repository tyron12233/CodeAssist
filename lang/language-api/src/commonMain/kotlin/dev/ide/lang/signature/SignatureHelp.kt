package dev.ide.lang.signature

import dev.ide.lang.incremental.DocumentSnapshot

/**
 * Parameter info / signature help — the IntelliJ "what arguments does this call take" popup. When the caret
 * is inside a call's argument list (`foo(|)`, `foo(1, |)`), the editor asks the backend for the callee's
 * parameters and floats a small panel *above* the call showing each overload's signature with the parameter
 * the caret currently sits on highlighted. It is a separate surface from code completion: completion offers
 * what to type, signature help reminds you of the shape you are typing into.
 *
 * Everything above this SPI talks only to [SignatureHelp] and its neutral, render-ready sub-types, so the
 * editor needs no language knowledge: a backend resolves the call however it likes (JDT bindings, the Kotlin
 * resolver, …) and returns labels + parameter ranges; the editor positions and styles them. Add new languages
 * by implementing this on a [dev.ide.lang.SourceAnalyzer], not by editing the host.
 */
interface SignatureHelpService {
    /** The signatures applicable at [SignatureHelpRequest.offset], or null when the caret is not inside a
     *  resolvable call (the editor then shows nothing / dismisses any open panel). */
    suspend fun signatureHelp(request: SignatureHelpRequest): SignatureHelp?
}

data class SignatureHelpRequest(
    val document: DocumentSnapshot,
    val offset: Int,
    val trigger: SignatureHelpTrigger,
)

/** Why signature help is being computed. Backends may use it to bias results; most can ignore it and simply
 *  resolve the call at the caret. */
sealed interface SignatureHelpTrigger {
    /** Explicit invocation (Ctrl/Cmd-P) — show even when no trigger character was just typed. */
    object Explicit : SignatureHelpTrigger
    /** A trigger character was typed, usually `(` (entered a call) or `,` (advanced to the next argument). */
    data class TypedChar(val char: Char) : SignatureHelpTrigger
    /** The caret moved or the buffer changed while a panel was (or could be) showing — re-resolve to update
     *  the active parameter / dismiss when the caret leaves the call. */
    object CursorUpdate : SignatureHelpTrigger
}

/**
 * The set of signatures the call at the caret could resolve to, plus which one and which parameter are active.
 * Usually [signatures] holds one entry; for an overloaded call it holds each overload so the user can see the
 * full set (the active one — best-matching the arguments typed so far — is [activeSignature]).
 */
data class SignatureHelp(
    val signatures: List<SignatureInfo>,
    /** Index into [signatures] of the overload that best matches the arguments typed so far. */
    val activeSignature: Int = 0,
    /** Zero-based index of the argument the caret sits in (the parameter to highlight). Past the last declared
     *  parameter for a vararg / too-many-args case; the editor clamps for highlighting. */
    val activeParameter: Int = 0,
)

/**
 * One callable's rendered signature. [label] is the whole thing as shown (`format(String fmt, Object... args)`);
 * each [ParameterInfo] carries the sub-range of [label] it occupies so the editor can bold/accent exactly the
 * active parameter without re-parsing the label.
 */
data class SignatureInfo(
    val label: String,
    val parameters: List<ParameterInfo>,
    val documentation: String? = null,
    /** Per-overload override of the active parameter (e.g. a vararg overload absorbs trailing args into its
     *  last parameter). Null → use [SignatureHelp.activeParameter]. */
    val activeParameter: Int? = null,
)

/**
 * One parameter within a [SignatureInfo]. [labelStart]/[labelEnd] are the `[start, end)` character offsets of
 * this parameter inside [SignatureInfo.label] (for highlighting); both -1 when the backend didn't locate it.
 */
data class ParameterInfo(
    val label: String,
    val labelStart: Int = -1,
    val labelEnd: Int = -1,
    val documentation: String? = null,
    /** True when this parameter has already been supplied by a named argument (`name = …`) earlier in the call
     *  the caret is in, so the editor can dim it — it no longer needs to be typed. Languages without named
     *  arguments leave this false. */
    val alreadyNamed: Boolean = false,
)
