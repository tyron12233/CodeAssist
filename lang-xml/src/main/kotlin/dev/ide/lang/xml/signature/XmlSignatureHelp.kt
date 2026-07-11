package dev.ide.lang.xml.signature

import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.signature.SignatureInfo
import dev.ide.lang.xml.completion.XmlCompletionKind
import dev.ide.lang.xml.completion.XmlCompletionPosition
import dev.ide.lang.xml.completion.XmlContextScanner

/**
 * Parameter-info / signature help for XML: when the caret is inside an attribute value (`android:x="|"`), it
 * floats a panel describing what that attribute expects (`true | false`, an enum's members, `@string/…`, …).
 *
 * `lang-xml` stays Android-agnostic: it locates the caret (reusing [XmlContextScanner], the same context the
 * completion engine derives) and asks the injected [valueHint] — the host's schema knowledge — to describe the
 * attribute's value; the label + rendering are the neutral [SignatureHelp] types. Returns null unless the caret
 * is in an attribute value and the host can describe it, so the editor shows nothing elsewhere.
 */
class XmlSignatureHelp(
    private val parse: (DocumentSnapshot) -> ParsedFile,
    private val valueHint: (XmlCompletionPosition) -> SignatureInfo?,
) : SignatureHelpService {

    override suspend fun signatureHelp(request: SignatureHelpRequest): SignatureHelp? {
        val doc = request.document
        val parsed = runCatching { parse(doc) }.getOrNull() ?: return null
        val pos = XmlContextScanner.scan(doc.text, request.offset, parsed, doc.file.path)
        if (pos.kind != XmlCompletionKind.ATTRIBUTE_VALUE) return null
        val info = valueHint(pos) ?: return null
        return SignatureHelp(signatures = listOf(info), activeSignature = 0, activeParameter = 0)
    }
}
