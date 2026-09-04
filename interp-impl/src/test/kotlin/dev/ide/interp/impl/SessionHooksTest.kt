package dev.ide.interp.impl

import dev.ide.interp.HookDecision as CoreDecision
import dev.ide.interp.api.HookDecision
import dev.ide.interp.api.InterpretHooks
import dev.ide.interp.api.SandboxCategories
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a session's sandbox and a plugin's own hooks compose.
 *
 * The order is the security-relevant part, and it is asserted directly rather than through a lowered program:
 * whether a plugin can widen what the user restricted must not depend on which library call a test harness
 * happens to be able to resolve.
 */
class SessionHooksTest {

    /** A call into `java.net.Socket`, which the sandbox classifies as network access for any member. */
    private fun socketCall(): RNode.Call = RNode.Call(
        ResolvedCallable.Library(
            displayName = "Socket",
            ownerFqn = "java.net.Socket",
            methodName = "<init>",
            paramTypes = emptyList(),
            isStatic = false,
            isConstructor = true,
            isInline = false,
        ),
        DispatchKind.CONSTRUCTOR,
        receiver = null,
        args = emptyList(),
        callSiteKey = CallSiteKey(0),
        source = SourceSpan(0, 0),
    )

    /** Hooks that allow everything, i.e. a plugin that tries to let the blocked call through. */
    private val permissive = object : InterpretHooks {}

    @Test
    fun `no categories and no hooks means no hooks at all, so nothing is checked`() {
        val hooks = SessionHooks.of(emptySet(), strict = false, hooks = null)
        assertNull(hooks.hooks, "a session that checks nothing should not pay for a hook chain")
        assertTrue(hooks.findings().isEmpty())
    }

    @Test
    fun `a restricted category stubs the call and records a finding`() {
        val hooks = SessionHooks.of(setOf(SandboxCategories.NETWORK), strict = false, hooks = null)
        val decision = hooks.hooks!!.beforeCall(socketCall(), receiver = null, args = emptyList())
        assertTrue(decision is CoreDecision.Replace, decision.toString())
        assertEquals(null, (decision as CoreDecision.Replace).value)
        val problems = hooks.findings()
        assertEquals(1, problems.size)
        assertTrue("network access" in problems.single().message, problems.toString())
        assertTrue("Socket" in problems.single().detail!!, problems.toString())
    }

    @Test
    fun `a strict session denies rather than stubs`() {
        val hooks = SessionHooks.of(setOf(SandboxCategories.NETWORK), strict = true, hooks = null)
        val decision = hooks.hooks!!.beforeCall(socketCall(), receiver = null, args = emptyList())
        assertTrue(decision is CoreDecision.Deny, decision.toString())
    }

    @Test
    fun `a plugin's hooks cannot widen the sandbox`() {
        val hooks = SessionHooks.of(setOf(SandboxCategories.NETWORK), strict = false, hooks = permissive)
        val decision = hooks.hooks!!.beforeCall(socketCall(), receiver = null, args = emptyList())
        assertTrue(decision is CoreDecision.Replace, "the sandbox decides first, so this stays blocked")
    }

    @Test
    fun `a plugin's hooks still see what the sandbox allowed`() {
        val seen = mutableListOf<String>()
        val recording = object : InterpretHooks {
            override fun beforeCall(
                ownerFqn: String?,
                member: String,
                receiver: Any?,
                args: List<Any?>,
            ): HookDecision {
                seen += "$ownerFqn.$member"
                return HookDecision.Proceed
            }
        }
        // File access is restricted; the network is not, so the socket call reaches the plugin.
        val hooks = SessionHooks.of(setOf(SandboxCategories.FILE_IO), strict = false, hooks = recording)
        hooks.hooks!!.beforeCall(socketCall(), receiver = null, args = emptyList())
        assertEquals(listOf("java.net.Socket.<init>"), seen)
    }

    @Test
    fun `a plugin can refuse what the sandbox allowed`() {
        val refusing = object : InterpretHooks {
            override fun beforeCall(
                ownerFqn: String?,
                member: String,
                receiver: Any?,
                args: List<Any?>,
            ): HookDecision = HookDecision.Deny("this preview opens no sockets")
        }
        val hooks = SessionHooks.of(emptySet(), strict = false, hooks = refusing)
        val decision = hooks.hooks!!.beforeCall(socketCall(), receiver = null, args = emptyList())
        assertTrue(decision is CoreDecision.Deny, decision.toString())
        assertEquals("this preview opens no sockets", (decision as CoreDecision.Deny).reason)
    }

    @Test
    fun `a constructor is reported as its type's init rather than by name`() {
        val seen = mutableListOf<String>()
        val recording = object : InterpretHooks {
            override fun beforeCall(
                ownerFqn: String?,
                member: String,
                receiver: Any?,
                args: List<Any?>,
            ): HookDecision {
                seen += member
                return HookDecision.Proceed
            }
        }
        SessionHooks.of(emptySet(), strict = false, hooks = recording)
            .hooks!!.beforeCall(socketCall(), receiver = null, args = emptyList())
        assertEquals(listOf("<init>"), seen)
    }

    @Test
    fun `class initialization is refused when either half refuses`() {
        val refusing = object : InterpretHooks {
            override fun beforeClassInit(fqn: String): Boolean = fqn != "com.example.Singleton"
        }
        val hooks = SessionHooks.of(setOf(SandboxCategories.NETWORK), strict = false, hooks = refusing)
        assertTrue(hooks.hooks!!.beforeClassInit("com.example.Other"))
        assertTrue(!hooks.hooks!!.beforeClassInit("com.example.Singleton"))
    }

    @Test
    fun `findings can be cleared between passes`() {
        val hooks = SessionHooks.of(setOf(SandboxCategories.NETWORK), strict = false, hooks = null)
        hooks.hooks!!.beforeCall(socketCall(), receiver = null, args = emptyList())
        assertTrue(hooks.findings().isNotEmpty())
        hooks.clearFindings()
        assertTrue(hooks.findings().isEmpty())
    }
}
