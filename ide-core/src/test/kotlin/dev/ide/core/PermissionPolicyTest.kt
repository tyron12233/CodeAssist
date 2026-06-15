package dev.ide.core

import dev.ide.build.engine.GuardCategory
import dev.ide.ui.backend.UiPermissionDecision
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run-sandbox decision memory: undecided until answered, then remembered per the chosen scope
 *  (once / this-run / always-persisted), with deny remembered for the run. */
class PermissionPolicyTest {

    @Test
    fun onceIsNotRemembered_runIsRemembered_untilReset() {
        val file = Files.createTempDirectory("perm").resolve("permissions.properties")
        try {
            val policy = PermissionPolicy(file)
            assertNull(policy.decided(GuardCategory.NETWORK), "undecided initially")

            assertTrue(policy.apply(GuardCategory.NETWORK, UiPermissionDecision.ALLOW_ONCE))
            assertNull(policy.decided(GuardCategory.NETWORK), "allow-once is not remembered")

            assertTrue(policy.apply(GuardCategory.NETWORK, UiPermissionDecision.ALLOW_RUN))
            assertEquals(true, policy.decided(GuardCategory.NETWORK), "allow-for-run is remembered")
            policy.resetRun()
            assertNull(policy.decided(GuardCategory.NETWORK), "run scope clears on reset")
        } finally { file.parent.toFile().deleteRecursively() }
    }

    @Test
    fun denyIsRememberedForTheRun() {
        val file = Files.createTempDirectory("perm").resolve("permissions.properties")
        try {
            val policy = PermissionPolicy(file)
            assertEquals(false, policy.apply(GuardCategory.EXEC, UiPermissionDecision.DENY))
            assertEquals(false, policy.decided(GuardCategory.EXEC), "deny holds for the run (no re-prompt loop)")
            policy.resetRun()
            assertNull(policy.decided(GuardCategory.EXEC), "deny clears on the next run")
        } finally { file.parent.toFile().deleteRecursively() }
    }

    @Test
    fun alwaysPersistsAcrossPolicyInstancesAndResets() {
        val dir = Files.createTempDirectory("perm")
        val file = dir.resolve(".platform/permissions.properties")
        try {
            PermissionPolicy(file).apply(GuardCategory.FILE_WRITE, UiPermissionDecision.ALLOW_ALWAYS)
            assertTrue(Files.exists(file), "always-allow is persisted")

            val reopened = PermissionPolicy(file)
            assertEquals(true, reopened.decided(GuardCategory.FILE_WRITE), "always survives a fresh policy (new session)")
            reopened.resetRun()
            assertEquals(true, reopened.decided(GuardCategory.FILE_WRITE), "always survives a run reset")
            assertNull(reopened.decided(GuardCategory.FILE_READ), "an unrelated category stays undecided")
        } finally { dir.toFile().deleteRecursively() }
    }
}
