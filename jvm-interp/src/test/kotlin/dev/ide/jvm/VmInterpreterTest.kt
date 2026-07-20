package dev.ide.jvm

import dev.ide.jvm.fixtures.Arithmetic
import dev.ide.jvm.fixtures.Bridged
import dev.ide.jvm.fixtures.ClassLit
import dev.ide.jvm.fixtures.Constants
import dev.ide.jvm.fixtures.Construct
import dev.ide.jvm.fixtures.Lambdas
import dev.ide.jvm.fixtures.Objects
import dev.ide.jvm.fixtures.Recover
import dev.ide.jvm.fixtures.Statics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Drives the bytecode VM against the Java test fixtures and uses the **real** method invocation as the oracle:
 * every case interprets a fixture's compiled `.class` and asserts the result equals calling that method for
 * real. The fixtures are compiled by the test source set, so the interpreter runs genuine javac output, not
 * hand-written bytecode. No app wiring; the VM is driven directly.
 */
class VmInterpreterTest {

    private val vm = Vm() // classpath source + default policy (interpret dev.ide.*, bridge java.*)

    private val ARITH = "dev/ide/jvm/fixtures/Arithmetic"
    private val OBJ = "dev/ide/jvm/fixtures/Objects"
    private val BRIDGED = "dev/ide/jvm/fixtures/Bridged"
    private val STATICS = "dev/ide/jvm/fixtures/Statics"
    private val LAMBDAS = "dev/ide/jvm/fixtures/Lambdas"
    private val CONSTRUCT = "dev/ide/jvm/fixtures/Construct"
    private val CLASSLIT = "dev/ide/jvm/fixtures/ClassLit"
    private val RECOVER = "dev/ide/jvm/fixtures/Recover"

    private fun call(owner: String, name: String, desc: String, vararg args: Any?): Any? =
        vm.invokeStatic(owner, name, desc, args.toList())

    // ---- pure arithmetic / control flow -----------------------------------------------------------

    @Test fun iterativeFib() {
        for (n in 0..25) assertEquals(Arithmetic.fib(n), call(ARITH, "fib", "(I)I", n), "fib($n)")
    }

    @Test fun recursiveFib() {
        for (n in 0..20) assertEquals(Arithmetic.fibRecursive(n), call(ARITH, "fibRecursive", "(I)I", n), "fibRecursive($n)")
    }

    @Test fun longFactorial() {
        for (n in 0..20) assertEquals(Arithmetic.factorial(n), call(ARITH, "factorial", "(I)J", n), "factorial($n)")
    }

    @Test fun euclidGcd() {
        for ((a, b) in listOf(48 to 36, 17 to 5, 100 to 0, 0 to 7, 1071 to 462)) {
            assertEquals(Arithmetic.gcd(a, b), call(ARITH, "gcd", "(II)I", a, b), "gcd($a,$b)")
        }
    }

    @Test fun bitwiseOps() {
        for ((a, b) in listOf(0xF0 to 0x0F, 12345 to 6789, -1 to 1, 255 to 128)) {
            assertEquals(Arithmetic.bitwise(a, b), call(ARITH, "bitwise", "(II)I", a, b), "bitwise($a,$b)")
        }
    }

    @Test fun mixedFloatingMath() {
        val r = call(ARITH, "mixedMath", "(IJFD)D", 3, 4L, 2.5f, 1.25) as Double
        assertEquals(Arithmetic.mixedMath(3, 4L, 2.5f, 1.25), r, 0.0, "mixedMath")
    }

    @Test fun ternaryAndCompare() {
        for (x in listOf(-5, 0, 3, 101, 250)) assertEquals(Arithmetic.branchy(x), call(ARITH, "branchy", "(I)I", x), "branchy($x)")
    }

    @Test fun tableSwitch() {
        for (code in listOf(1, 2, 5, 10, 3, 0)) assertEquals(Arithmetic.switchColor(code), call(ARITH, "switchColor", "(I)I", code), "switch($code)")
    }

    @Test fun divideByZeroThrowsRealException() {
        assertEquals(Arithmetic.intDiv(10, 3), call(ARITH, "intDiv", "(II)I", 10, 3))
        assertFailsWith<ArithmeticException> { call(ARITH, "intDiv", "(II)I", 10, 0) }
    }

    // ---- objects / arrays / exceptions ------------------------------------------------------------

    @Test fun objectFieldsAndLoops() {
        for ((s, t) in listOf(0 to 5, 100 to 0, -3 to 10)) {
            assertEquals(Objects.counter(s, t), call(OBJ, "counter", "(II)I", s, t), "counter($s,$t)")
        }
    }

    @Test fun virtualDispatchAndSuper() {
        assertEquals(Objects.polymorphism(0), call(OBJ, "polymorphism", "(I)I", 0), "Triangle")
        assertEquals(Objects.polymorphism(1), call(OBJ, "polymorphism", "(I)I", 1), "Square (super call)")
    }

    @Test fun arraysAndForEach() {
        for (n in listOf(0, 1, 5, 10)) assertEquals(Objects.arraySum(n), call(OBJ, "arraySum", "(I)I", n), "arraySum($n)")
    }

    @Test fun multiDimensionalArray() {
        for (n in listOf(1, 3, 6)) assertEquals(Objects.matrixTrace(n), call(OBJ, "matrixTrace", "(I)I", n), "matrixTrace($n)")
    }

    @Test fun tryCatchArithmetic() {
        assertEquals(Objects.catchDivByZero(10, 2), call(OBJ, "catchDivByZero", "(II)I", 10, 2))
        assertEquals(Objects.catchDivByZero(10, 0), call(OBJ, "catchDivByZero", "(II)I", 10, 0), "caught /0 → -1")
    }

    @Test fun tryCatchFinallyArrayBounds() {
        assertEquals(Objects.catchArrayBounds(3, 1), call(OBJ, "catchArrayBounds", "(II)I", 3, 1))
        assertEquals(Objects.catchArrayBounds(3, 9), call(OBJ, "catchArrayBounds", "(II)I", 3, 9), "caught OOB → -999")
    }

    @Test fun instanceofCheck() {
        assertEquals(Objects.instanceCheck(0), (call(OBJ, "instanceCheck", "(I)Z", 0) as Int) != 0)
        assertEquals(Objects.instanceCheck(1), (call(OBJ, "instanceCheck", "(I)Z", 1) as Int) != 0)
    }

    // ---- static initialization --------------------------------------------------------------------

    @Test fun clinitAndStaticState() {
        assertEquals(Statics.base(), call(STATICS, "base", "()I"), "<clinit> computed BASE")
        // Two bumps on a freshly-initialized class: 42 -> 44 (proves static get/put + init order).
        assertEquals(44, call(STATICS, "bumpTwice", "()I"), "counter seeded from BASE then bumped twice")
    }

    @Test fun reflectiveInstantiationOfAnInterpretedClass() {
        // loadClass -> asSubclass -> getConstructor -> newInstance over interpreted types (the pattern a
        // framework uses to build a class named by a resource, e.g. CoordinatorLayout's layout_behavior).
        val RI = "dev/ide/jvm/fixtures/ReflectInstantiate"
        val cl = ClassLoader.getSystemClassLoader()
        val dog = "dev.ide.jvm.fixtures.ReflectInstantiate\$Dog"
        assertEquals(4, vm.invokeStatic(RI, "reflect", "(Ljava/lang/ClassLoader;Ljava/lang/String;I)I", listOf(cl, dog, 4)))
        // sameClass returns Z; invokeStatic yields a boolean in the interpreter's computational form (1 = true),
        // proving loadClass(name) and the class literal resolve to the SAME reflection Class.
        assertEquals(1, vm.invokeStatic(RI, "sameClass", "(Ljava/lang/ClassLoader;Ljava/lang/String;)Z", listOf(cl, dog)))
    }

    @Test fun staticFinalConstantsHonorConstantValueAttribute() {
        // A `static final` constant is stored in the ConstantValue attribute, not assigned in <clinit>, so the
        // VM must seed it at load; reading by name (as a getstatic against a non-inlined field does) must return
        // the constant, not the descriptor default. This is the path a library takes reading a regenerated R.
        val C = "dev.ide.jvm.fixtures.Constants"
        assertEquals(Constants.INT, vm.interpretedStaticValue(C, "INT"), "int constant")
        assertEquals(Constants.LONG, vm.interpretedStaticValue(C, "LONG"), "long constant")
        assertEquals(Constants.BOOL, vm.interpretedStaticValue(C, "BOOL"), "boolean constant")
        assertEquals(Constants.CH, vm.interpretedStaticValue(C, "CH"), "char constant")
        assertEquals(Constants.STR, vm.interpretedStaticValue(C, "STR"), "String constant")
    }

    // ---- the bridge to real platform classes ------------------------------------------------------

    @Test fun bridgedStaticMathCall() {
        for ((a, b) in listOf(3 to 7, -2 to 9, 5 to 5)) {
            assertEquals(Bridged.maxOf(a, b), call(BRIDGED, "maxOf", "(II)I", a, b), "maxOf($a,$b)")
        }
    }

    @Test fun bridgedInstanceStringCall() {
        for (s in listOf("", "hello", "interpreter")) {
            assertEquals(Bridged.stringLength(s), call(BRIDGED, "stringLength", "(Ljava/lang/String;)I", s), "length($s)")
            assertEquals(Bridged.charAtSum(s), call(BRIDGED, "charAtSum", "(Ljava/lang/String;)I", s), "charAtSum($s)")
        }
    }

    // ---- invokedynamic: lambdas, method references, string concatenation --------------------------

    @Test fun stringConcatenation() {
        assertEquals(Bridged.concat(7), call(BRIDGED, "concat", "(I)Ljava/lang/String;", 7))
        for (name in listOf("row", "tile")) for (n in listOf(0, 3, 42)) {
            assertEquals(Lambdas.describe(name, n), call(LAMBDAS, "describe", "(Ljava/lang/String;I)Ljava/lang/String;", name, n), "describe($name,$n)")
        }
    }

    @Test fun capturingLambdaWithinInterpretedCode() {
        for ((x, by) in listOf(5 to 1, 10 to 3, -2 to 4)) {
            assertEquals(Lambdas.incTwice(x, by), call(LAMBDAS, "incTwice", "(II)I", x, by), "incTwice($x,$by)")
        }
    }

    @Test fun multipleLambdas() {
        for ((a, b) in listOf(2 to 3, 5 to 7, 0 to 9)) {
            assertEquals(Lambdas.combine(a, b), call(LAMBDAS, "combine", "(II)I", a, b), "combine($a,$b)")
        }
    }

    @Test fun methodReferenceToPlatformMethod() {
        for (s in listOf("", "abc", "interpreter")) {
            assertEquals(Lambdas.refLength(s), call(LAMBDAS, "refLength", "(Ljava/lang/String;)I", s), "refLength($s)")
        }
    }

    @Test fun lambdaPassedToPlatformApi() {
        for ((n, add) in listOf(0 to 1, 5 to 2, 10 to 0)) {
            assertEquals(Lambdas.mapSum(n, add), call(LAMBDAS, "mapSum", "(II)I", n, add), "mapSum($n,$add)")
        }
    }

    @Test fun records() {
        assertEquals(Lambdas.recordToString(), call(LAMBDAS, "recordToString", "()Ljava/lang/String;"))
        assertEquals(Lambdas.recordEquals(), (call(LAMBDAS, "recordEquals", "()Z") as Int) != 0)
        assertEquals(Lambdas.recordHashConsistent(), (call(LAMBDAS, "recordHashConsistent", "()Z") as Int) != 0)
    }

    // ---- construction of platform classes ---------------------------------------------------------

    @Test fun buildsAPlatformObject() {
        for ((who, n) in listOf("world" to 1, "vm" to 42)) {
            assertEquals(Construct.build(who, n), call(CONSTRUCT, "build", "(Ljava/lang/String;I)Ljava/lang/String;", who, n), "build($who,$n)")
        }
    }

    @Test fun constructsAndUsesACollection() {
        for (n in listOf(0, 1, 5, 12)) assertEquals(Construct.listSum(n), call(CONSTRUCT, "listSum", "(I)I", n), "listSum($n)")
    }

    @Test fun throwsAConstructedException() {
        assertEquals(Construct.safeDiv(10, 2), call(CONSTRUCT, "safeDiv", "(II)I", 10, 2))
        assertEquals(Construct.safeDiv(10, 0), call(CONSTRUCT, "safeDiv", "(II)I", 10, 0), "constructed + thrown + caught")
    }

    @Test fun nestedConstruction() {
        assertEquals(Construct.nestedCause(), call(CONSTRUCT, "nestedCause", "()Ljava/lang/String;"))
    }

    // ---- class literals ---------------------------------------------------------------------------

    @Test fun classLiterals() {
        assertEquals(ClassLit.stringClassName(), call(CLASSLIT, "stringClassName", "()Ljava/lang/String;"))
        assertEquals(ClassLit.intArrayClassName(), call(CLASSLIT, "intArrayClassName", "()Ljava/lang/String;"))
        assertEquals(ClassLit.isString("x"), (call(CLASSLIT, "isString", "(Ljava/lang/Object;)Z", "x") as Int) != 0)
        assertEquals(ClassLit.isString(42), (call(CLASSLIT, "isString", "(Ljava/lang/Object;)Z", 42) as Int) != 0)
    }

    // ---- recovery: bridge exceptions and array mutation -------------------------------------------

    @Test fun platformExceptionCaughtByInterpretedHandler() {
        // Integer.parseInt (a bridge call) throws NumberFormatException, caught by the interpreted try/catch.
        assertEquals(Recover.parseOrDefault("41", -1), call(RECOVER, "parseOrDefault", "(Ljava/lang/String;I)I", "41", -1))
        assertEquals(Recover.parseOrDefault("nope", -1), call(RECOVER, "parseOrDefault", "(Ljava/lang/String;I)I", "nope", -1), "caught NumberFormatException")
    }

    @Test fun platformMutatesInterpretedArrayInPlace() {
        assertEquals(Recover.sortFirst(3, 1, 2), call(RECOVER, "sortFirst", "(III)I", 3, 1, 2), "Arrays.sort in place")
        for (n in listOf(0, 4)) assertEquals(Recover.fillSum(n, 7), call(RECOVER, "fillSum", "(II)I", n, 7), "Arrays.fill in place")
    }
}
