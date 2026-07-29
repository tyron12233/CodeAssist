package dev.ide.jvm

import dev.ide.jvm.kfixtures.classify
import dev.ide.jvm.kfixtures.copyX
import dev.ide.jvm.kfixtures.dpAdd
import dev.ide.jvm.kfixtures.dpScaled
import dev.ide.jvm.kfixtures.elvis
import dev.ide.jvm.kfixtures.greetFull
import dev.ide.jvm.kfixtures.greetWithDefault
import dev.ide.jvm.kfixtures.higherOrder
import dev.ide.jvm.kfixtures.manhattan
import dev.ide.jvm.kfixtures.pointEquals
import dev.ide.jvm.kfixtures.pointHashStable
import dev.ide.jvm.kfixtures.pointToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Interprets compiled **Kotlin** bytecode (the shapes Jetpack Compose relies on: inline value classes, data
 * classes, default arguments, string templates, lambdas, null-check intrinsics), checking each against the
 * real function as the oracle. The fixtures are compiled by the test source set with the same Kotlin compiler
 * the project uses, so this exercises genuine Kotlin bytecode. The Kotlin stdlib (`kotlin.*`) is bridged.
 */
class KotlinBytecodeTest {

    private val vm = Vm()
    private val KFX = "dev/ide/jvm/kfixtures/KFxKt"

    private fun call(name: String, desc: String, vararg args: Any?): Any? = vm.invokeStatic(KFX, name, desc, args.toList())

    /**
     * A `boolean`/`byte`/`char`/`short`-returning lambda linked via `LambdaMetafactory` (its impl returns the
     * primitive, adapted to the erased `Function0.invoke():Object`) must box to ITS OWN wrapper when its result
     * crosses to real code — not default to `Integer`, since the interpreter represents all four as `Int`.
     * Regression for a Compose preview crash: `((Boolean) LocalUsingExpressiveTheme's default factory.invoke())`
     * threw `ClassCastException: cannot cast java.lang.Integer to java.lang.Boolean`.
     */
    @Test fun primitiveReturningLambdaBoxesToItsOwnWrapperAcrossTheBridge() {
        fun invoked(name: String): Any? = call(name, "()Ljava/lang/Object;")
        fun typeOf(name: String): String = invoked(name)!!.javaClass.name
        assertEquals("java.lang.Boolean", typeOf("boolViaBridge"), "boolean lambda")
        assertEquals(false, invoked("boolViaBridge"))
        assertEquals("java.lang.Byte", typeOf("byteViaBridge"), "byte lambda")
        assertEquals(7.toByte(), invoked("byteViaBridge"))
        assertEquals("java.lang.Character", typeOf("charViaBridge"), "char lambda")
        assertEquals('Q', invoked("charViaBridge"))
        assertEquals("java.lang.Short", typeOf("shortViaBridge"), "short lambda")
        assertEquals(9.toShort(), invoked("shortViaBridge"))
        assertEquals("java.lang.Integer", typeOf("intViaBridge"), "int lambda (control)")
        assertEquals(42, invoked("intViaBridge"))
    }

    @Test fun inlineValueClass() {
        assertEquals(dpAdd(3f, 4f), call("dpAdd", "(FF)F", 3f, 4f))
        assertEquals(dpScaled(2f, 2.5f), call("dpScaled", "(FF)F", 2f, 2.5f))
    }

    @Test fun dataClassGeneratedMembers() {
        assertEquals(pointToString(1, 2), call("pointToString", "(II)Ljava/lang/String;", 1, 2))
        assertEquals(pointEquals(1, 2), (call("pointEquals", "(II)Z", 1, 2) as Int) != 0)
        assertEquals(pointHashStable(1, 2), (call("pointHashStable", "(II)Z", 1, 2) as Int) != 0)
        assertEquals(manhattan(-3, 4), call("manhattan", "(II)I", -3, 4))
    }

    @Test fun dataClassCopyViaDefaultSynthetic() {
        assertEquals(copyX(1, 2, 9), call("copyX", "(III)Ljava/lang/String;", 1, 2, 9))
    }

    @Test fun stringTemplatesAndDefaults() {
        assertEquals(greetFull("VM", "!"), call("greetFull", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", "VM", "!"))
        assertEquals(greetWithDefault("VM"), call("greetWithDefault", "(Ljava/lang/String;)Ljava/lang/String;", "VM"))
    }

    @Test fun controlFlowAndLambdas() {
        for (n in listOf(-2, 0, 7)) assertEquals(classify(n), call("classify", "(I)Ljava/lang/String;", n), "classify($n)")
        assertEquals(elvis(3, -1), call("elvis", "(II)I", 3, -1))
        assertEquals(elvis(-3, -1), call("elvis", "(II)I", -3, -1))
        assertEquals(higherOrder(20), call("higherOrder", "(I)I", 20))
    }
}
