package dev.ide.jvm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which classes [InterpretPolicy.withHostFallback] takes off the bridge.
 *
 * The case it exists for is the R8-minified app: kotlinx-collections-immutable ships inside it under renamed
 * classes, so a console program's `kotlinx/collections/immutable/ExtensionsKt` was bridged to a class the host
 * no longer has under that name. Here a made-up `kotlinx/` class stands in for it: absent from the host either way.
 */
class InterpretPolicyHostFallbackTest {

    private val policy = InterpretPolicy.withHostFallback(javaClass.classLoader)

    @Test fun aLibraryClassTheHostLacksIsInterpreted() {
        assertTrue(policy.interpret("kotlinx/r8gone/Greeter"))
        assertTrue(policy.interpret("androidx/r8gone/Widget"))
        assertTrue(policy.interpret("org/jetbrains/r8gone/Thing"))
    }

    @Test fun aLibraryClassTheHostLoadsStaysBridged() {
        // The IDE's own copy keeps its identity: the program and the host must share these types.
        assertFalse(policy.interpret("kotlin/collections/CollectionsKt"))
        assertFalse(policy.interpret("kotlin/Unit"))
    }

    @Test fun thePlatformNeverFallsBack() {
        // A classpath has no business supplying java.* or android.*, even a class the host doesn't have.
        assertFalse(policy.interpret("java/lang/R8Gone"))
        assertFalse(policy.interpret("android/r8gone/View"))
    }

    @Test fun theBasePolicyStillDecidesEverythingElse() {
        assertTrue(policy.interpret("com/example/Main"))
        val bridgeAll = InterpretPolicy.withHostFallback(javaClass.classLoader, InterpretPolicy { false })
        assertFalse(bridgeAll.interpret("com/example/Main"))
        assertTrue(bridgeAll.interpret("kotlinx/r8gone/Greeter"))
    }
}
