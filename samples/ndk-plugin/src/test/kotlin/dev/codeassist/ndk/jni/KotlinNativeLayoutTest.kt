package dev.codeassist.ndk.jni

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Where kotlinc actually puts the `native` method for each shape of `external fun`, read off the compiled
 * classes, because that is what decides the JNI symbol the scanner has to predict.
 */
class KotlinNativeLayoutTest {

    class Probe {
        companion object {
            @JvmStatic external fun staticInCompanion(): Long
            external fun plainInCompanion(): Long
        }
    }

    object ProbeObject {
        @JvmStatic external fun staticInObject()
        external fun plainInObject()
    }

    private fun natives(cls: Class<*>): Map<String, Boolean> =
        cls.declaredMethods.filter { Modifier.isNative(it.modifiers) }
            .associate { it.name to Modifier.isStatic(it.modifiers) }

    @Test
    fun jvmStaticInACompanionIsAStaticNativeOnTheEnclosingClass() {
        assertEquals(mapOf("staticInCompanion" to true), natives(Probe::class.java))
        // The companion keeps only the plain one native; its `staticInCompanion` delegates.
        assertEquals(mapOf("plainInCompanion" to false), natives(Probe.Companion::class.java))
    }

    @Test
    fun anObjectsJvmStaticIsStaticAndTheRestAreInstanceMethods() {
        assertEquals(mapOf("staticInObject" to true, "plainInObject" to false), natives(ProbeObject::class.java))
    }
}
