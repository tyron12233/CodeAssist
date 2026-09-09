package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A top-level extension property's getter sees its receiver as the implicit `this`: a BARE member read
 * (`v`, not `this.v`) must resolve and run. JetNews's `PostContent.kt` reads
 * `val ColorScheme.codeBlockBackground get() = onSurface.copy(alpha = .15f)`; the resolver's implicit-receiver
 * walk covered extension FUNCTIONS only, so `onSurface` was "Unresolved reference" and the preview refused to
 * interpret the file.
 */
class ExtensionPropertyReceiverScopeTest {

    @Test
    fun bareMemberReadInsideAnExtensionPropertyGetterResolvesToTheReceiver() {
        // The JetNews shape: a bare RECEIVER MEMBER read (`v`, cf. `onSurface`) followed by a call on it
        // (`.plus(...)`, cf. `.copy(alpha = …)`), and a bare read of ANOTHER extension property of the receiver.
        val code = """
            class Paint(val a: Int) { fun copy(alpha: Int): Paint = Paint(alpha) }
            class Box(val onSurface: Paint)
            val Box.doubled: Int
                get() = onSurface.copy(alpha = onSurface.a).a * 2
            val Box.quadrupled: Int get() = doubled * 2
            fun use(): Int = Box(Paint(21)).doubled + Box(Paint(1)).quadrupled
        """.trimIndent()
        assertEquals(42 + 4, runProgram(code, "use/0", emptyList()))
    }
}
