package dev.ide.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ART-runtime detection behind the SDK Manager's JDK card — on ART the "running JDK" is the Android
 *  runtime (java.home `/apex/com.android.art`, java.version `0`), which must be labelled as such, not "JDK 0". */
class JdkManagerTest {

    @Test
    fun artRuntimeIsDetected() {
        // ART/Dalvik reports these for backwards compatibility.
        assertTrue(JdkManager.isAndroidRuntime("Dalvik", "The Android Project"))
        assertTrue(JdkManager.isAndroidRuntime(null, "The Android Project"))
        assertTrue(JdkManager.isAndroidRuntime("Dalvik", null))
    }

    @Test
    fun desktopJvmIsNotAndroidRuntime() {
        assertFalse(JdkManager.isAndroidRuntime("OpenJDK 64-Bit Server VM", "Eclipse Adoptium"))
        assertFalse(JdkManager.isAndroidRuntime("Java HotSpot(TM) 64-Bit Server VM", "Oracle Corporation"))
        assertFalse(JdkManager.isAndroidRuntime(null, null))
    }
}
