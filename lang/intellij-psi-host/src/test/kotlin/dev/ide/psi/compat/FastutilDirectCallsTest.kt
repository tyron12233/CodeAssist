package dev.ide.psi.compat

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FastutilDirectCalls] must behave exactly like the interface calls it replaces, for the concrete classes
 * IntelliJ uses and for any other implementation. And the real `MarkerOptionalData` /
 * `PsiBuilderImpl$MyTreeStructure` this build ships must not make a fastutil interface call the shim lacks:
 * `FastutilInterfaceDispatchArtPass` fails the Android build on one, and this catches it on the JVM first.
 */
class FastutilDirectCallsTest {

    @Test
    fun intSetShimsMatchTheInterfaceCalls() {
        for (set in listOf(IntOpenHashSet(), IntAVLTreeSet())) {
            assertTrue(FastutilDirectCalls.intSetAdd(set, 7))
            assertFalse(FastutilDirectCalls.intSetAdd(set, 7))
            assertTrue(FastutilDirectCalls.intSetContains(set, 7))
            assertFalse(FastutilDirectCalls.intSetContains(set, 8))
            assertTrue(FastutilDirectCalls.intSetRemove(set, 7))
            assertFalse(FastutilDirectCalls.intSetRemove(set, 7))
            assertFalse(FastutilDirectCalls.intSetContains(set, 7))
        }
    }

    @Test
    fun int2ObjectShimsMatchTheInterfaceCalls() {
        for (map in listOf(Int2ObjectOpenHashMap<String>(), Int2ObjectRBTreeMap<String>())) {
            assertNull(FastutilDirectCalls.int2ObjectPut(map, 3, "a"))
            assertEquals("a", FastutilDirectCalls.int2ObjectPut(map, 3, "b"))
            assertEquals("b", FastutilDirectCalls.int2ObjectGet(map, 3))
            assertNull(FastutilDirectCalls.int2ObjectGet(map, 4))
            assertEquals("b", FastutilDirectCalls.int2ObjectRemove(map, 3))
            assertNull(FastutilDirectCalls.int2ObjectGet(map, 3))
        }
    }

    @Test
    fun everyFastutilInterfaceCallInThePsiBuilderHasAShim() {
        val shims = FastutilDirectCalls::class.java.declaredMethods.map { Type.getMethodDescriptor(it) }.toSet()
        val calls = listOf(
            "com/intellij/lang/impl/MarkerOptionalData",
            "com/intellij/lang/impl/PsiBuilderImpl\$MyTreeStructure",
        ).flatMap(::fastutilInterfaceCalls)

        // Both classes still exist and still make these calls; if IntelliJ drops them, retire the pass.
        assertTrue(calls.any { it.startsWith("it/unimi/dsi/fastutil/ints/IntSet.contains") }, "calls: $calls")
        for (call in calls) {
            val owner = call.substringBefore('.')
            val descriptor = call.substring(call.indexOf('('))
            val shimDescriptor = "(L$owner;${descriptor.substring(1)}"
            assertTrue(shimDescriptor in shims, "no FastutilDirectCalls method for $call (want $shimDescriptor)")
        }
    }

    private fun fastutilInterfaceCalls(internalName: String): List<String> {
        val bytes = javaClass.classLoader.getResourceAsStream("$internalName.class")!!.use { it.readBytes() }
        val calls = mutableListOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean,
                ) {
                    if (opcode == Opcodes.INVOKEINTERFACE && owner.startsWith("it/unimi/dsi/fastutil/")) {
                        calls += "$owner.$name$descriptor"
                    }
                }
            }
        }, 0)
        return calls
    }
}
