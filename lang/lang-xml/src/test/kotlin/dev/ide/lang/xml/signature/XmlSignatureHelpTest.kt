package dev.ide.lang.xml.signature

import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpTrigger
import dev.ide.lang.signature.SignatureInfo
import dev.ide.lang.xml.TestDoc
import dev.ide.lang.xml.XmlIncrementalParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmlSignatureHelpTest {

    // A fake host schema: describe any attribute whose value the caret is inside.
    private val svc = XmlSignatureHelp(
        parse = { XmlIncrementalParser().parseFull(it) },
        valueHint = { pos -> pos.attributeName?.let { SignatureInfo("$it = <hint>", emptyList()) } },
    )

    private fun help(xmlWithCaret: String): SignatureHelp? {
        val offset = xmlWithCaret.indexOf('|')
        val text = xmlWithCaret.removeRange(offset, offset + 1)
        return runBlocking {
            svc.signatureHelp(SignatureHelpRequest(TestDoc(text), offset, SignatureHelpTrigger.CursorUpdate))
        }
    }

    @Test
    fun showsHintInsideAttributeValue() {
        val h = help("<TextView android:text=\"|\" />")
        assertNotNull(h, "expected signature help inside the value")
        assertTrue(h.signatures.single().label.contains("android:text"), "got ${h.signatures}")
    }

    @Test
    fun noHintAtAttributeNameOrTag() {
        assertNull(help("<TextView an|droid:text=\"x\" />"), "no hint on an attribute NAME")
        assertNull(help("<Text|View android:text=\"x\" />"), "no hint on a tag name")
    }
}
