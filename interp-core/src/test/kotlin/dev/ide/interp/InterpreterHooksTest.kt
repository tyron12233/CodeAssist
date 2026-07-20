package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The interpreter hook seam ([InterpreterHooks]) + the preview sandbox policy over it
 * ([PreviewSandboxPolicy]): a restricted library call / property read is stubbed (null + a recorded
 * finding) in the editor-preview mode and thrown in strict mode, while everything outside the curated
 * tables — including the members previews legitimately need — dispatches exactly as before.
 */
class InterpreterHooksTest {

    private val span = SourceSpan(0, 0)

    private fun restrictAll(stub: Boolean = true) =
        PreviewSandboxPolicy(SandboxCategory.entries.toSet(), stubOnDeny = stub)

    private fun run(body: RNode, hooks: InterpreterHooks?): Any? =
        Interpreter(emptyMap(), hooks = hooks)
            .call(ResolvedFunction("f", emptyList(), RNode.Block(listOf(body), false, span), emptyList()), emptyList())

    private fun objRef(fqn: String, name: String) = RNode.Name(Binding.ObjectRef(fqn, name), span)

    private fun library(name: String, owner: String, static: Boolean = false, constructor: Boolean = false) =
        ResolvedCallable.Library(
            displayName = name, ownerFqn = owner, methodName = name,
            paramTypes = emptyList(), isStatic = static, isConstructor = constructor,
            isInline = false, descriptorPrecise = true,
        )

    private fun call(callee: ResolvedCallable.Library, dispatch: DispatchKind, receiver: RNode?) =
        RNode.Call(callee, dispatch, receiver = receiver, args = emptyList(), callSiteKey = CallSiteKey(0), source = span)

    // ---- call gate --------------------------------------------------------------------------------

    @Test
    fun blockedStaticCallIsStubbedAndRecorded() {
        val policy = restrictAll()
        val env = call(library("getenv", "java.lang.System", static = true), DispatchKind.MEMBER, objRef("java.lang.System", "System"))
        assertNull(run(env, policy), "a blocked call must stub to null, not reach System.getenv")
        val finding = policy.findings().single()
        assertEquals(SandboxCategory.PROCESS_CONTROL, finding.category)
        assertEquals("java.lang.System.getenv", finding.member)
    }

    @Test
    fun strictModeThrowsSecurityException() {
        val env = call(library("getenv", "java.lang.System", static = true), DispatchKind.MEMBER, objRef("java.lang.System", "System"))
        val e = assertFailsWith<InterpreterSecurityException> { run(env, restrictAll(stub = false)) }
        assertTrue(e is InterpreterException, "a hook refusal must still be an InterpreterException for boundary handling")
        assertTrue("process/reflection" in (e.message ?: ""), "the reason should name the category; got: ${e.message}")
    }

    @Test
    fun allowedStaticCallStillDispatches() {
        val before = System.currentTimeMillis()
        val now = run(
            call(library("currentTimeMillis", "java.lang.System", static = true), DispatchKind.MEMBER, objRef("java.lang.System", "System")),
            restrictAll(),
        )
        assertTrue(now is Long && now >= before, "currentTimeMillis isn't in a restricted table and must run; got $now")
    }

    @Test
    fun memberCallOnFileReceiverIsBlockedByRuntimeClass() {
        // `file.exists()` — the receiver's RUNTIME class classifies it (java.io.File + a non-pure member),
        // regardless of the resolved owner.
        val policy = restrictAll()
        val exists = call(library("exists", "java.io.File"), DispatchKind.MEMBER, RNode.Const(File("/definitely/missing"), null, span))
        assertNull(run(exists, policy))
        assertEquals(SandboxCategory.FILE_IO, policy.findings().single().category)
    }

    @Test
    fun extensionReadTextOnFileIsBlocked() {
        // `file.readText()` — resolved to the kotlin.io facade, receiver is the File; either route classifies.
        val policy = restrictAll()
        val read = call(library("readText", "kotlin.io.FilesKt__FileReadWriteKt"), DispatchKind.EXTENSION, RNode.Const(File("/x"), null, span))
        assertNull(run(read, policy))
        assertEquals(SandboxCategory.FILE_IO, policy.findings().single().category)
    }

    @Test
    fun constructorOfRestrictedTypeIsBlocked() {
        val policy = restrictAll()
        val ctor = call(library("ProcessBuilder", "java.lang.ProcessBuilder", constructor = true), DispatchKind.CONSTRUCTOR, receiver = null)
        assertNull(run(ctor, policy))
        assertEquals(SandboxCategory.PROCESS_CONTROL, policy.findings().single().category)
    }

    // ---- property gate ----------------------------------------------------------------------------

    @Test
    fun systemOutReadStaysAllowed() {
        // Regression for the member-form matching: blocking `setOut` must NOT block the `System.out` READ
        // (the plain form `out` is not in the table; only a blind get/set strip would collide).
        val get = RNode.PropertyGet(objRef("java.lang.System", "System"), Binding.Property("out", null, backingField = false), span)
        assertSame(System.out, run(get, restrictAll()))
    }

    @Test
    fun purePathMembersOfFileStayAllowed() {
        val policy = restrictAll()
        assertEquals(HookDecision.Proceed, policy.beforePropertyRead(null, "name", File("/tmp/x.txt")))
        assertEquals(HookDecision.Proceed, policy.beforePropertyRead(null, "getPath", File("/tmp/x.txt")))
        assertTrue(policy.findings().isEmpty())
    }

    @Test
    fun fileSystemReadsAreBlockedThroughEveryPropertySpelling() {
        val policy = restrictAll()
        // A static facade read and both spellings of an instance accessor classify identically.
        assertTrue(policy.beforePropertyRead("java.nio.file.Files", "readString", null) is HookDecision.Replace)
        assertTrue(policy.beforePropertyRead(null, "exists", File("/x")) is HookDecision.Replace)
        assertTrue(policy.beforePropertyRead(null, "listFiles", File("/x")) is HookDecision.Replace)
        assertEquals(setOf(SandboxCategory.FILE_IO), policy.findings().map { it.category }.toSet())
    }

    @Test
    fun categoriesOutsideTheRestrictedSetProceed() {
        val fileOnly = PreviewSandboxPolicy.fromIds(listOf("fileIo"))
        assertTrue(fileOnly.beforePropertyRead(null, "exists", File("/x")) is HookDecision.Replace)
        // Network isn't restricted for this policy, so a socket-ish member proceeds.
        assertEquals(HookDecision.Proceed, fileOnly.beforePropertyRead("java.net.InetAddress", "getByName", null))
    }

    // ---- class-init gate --------------------------------------------------------------------------

    @Test
    fun classInitGateVetoesRestrictedOwners() {
        val policy = restrictAll()
        assertTrue(policy.beforeClassInit("okhttp3.OkHttpClient").not(), "an owner-wide network type must not static-init")
        assertTrue(policy.beforeClassInit("kotlin.Unit"), "an unrestricted owner must init normally")
        assertEquals(SandboxCategory.NETWORK, policy.findings().single().category)
    }

    // ---- findings lifecycle -----------------------------------------------------------------------

    @Test
    fun findingsDedupeAndClear() {
        val policy = restrictAll()
        repeat(3) { policy.beforePropertyRead(null, "exists", File("/x")) }
        assertEquals(1, policy.findings().size, "the same member must record once")
        policy.clearFindings()
        assertTrue(policy.findings().isEmpty())
        policy.beforePropertyRead(null, "exists", File("/x"))
        assertEquals(1, policy.findings().size, "recording isn't memoized away — it re-records after a clear")
    }
}
