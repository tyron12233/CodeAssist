package dev.ide.jvm

import dev.ide.jvm.fixtures.RuntimeClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An interpreted type's runtime `Class`. There is no real class for such a type, so the VM hands out the class
 * standing for it and services the questions asked of it against the interpreter. Each case is checked against
 * the same construct compiled and run for real.
 *
 * `getClass()` used to answer with the object's PEER, which was wrong twice over: peers are keyed by shape, so
 * every interpreted class with no real supertype and no `Object` override shared one class, and a peer carries
 * only its class's REAL interfaces. Reading a type argument off a value's `getClass()` therefore found a single
 * class, named after an unrelated type, implementing nothing the value's class declared.
 */
class VmRuntimeClassTest {

    private val vm = Vm()
    private val FIX = "dev/ide/jvm/fixtures/RuntimeClass"

    private fun bool(name: String) =
        assertEquals(
            if (RuntimeClass::class.java.getMethod(name).invoke(null) as Boolean) 1 else 0,
            vm.invokeStatic(FIX, name, "()Z"),
            name,
        )

    private fun string(name: String) =
        assertEquals(
            RuntimeClass::class.java.getMethod(name).invoke(null),
            vm.invokeStatic(FIX, name, "()Ljava/lang/String;"),
            name,
        )

    @Test fun theClassLiteralIsTheRuntimeClass() = bool("literalIsRuntimeClass")

    @Test fun unrelatedTypesDoNotShareARuntimeClass() = bool("unrelatedTypesHaveDistinctClasses")

    @Test fun theRuntimeClassNameResolvesBackToTheType() = bool("forNameRoundTrips")

    @Test fun instanceChecksFollowTheInterpretedHierarchy() {
        bool("recognizesItsOwnInstance")
        bool("rejectsAnotherTypesInstance")
        bool("baseIsAssignableFromSubclass")
        bool("subclassIsNotAssignableFromBase")
        bool("interfaceIsAssignableFromImplementation")
    }

    @Test fun castYieldsTheSameInstance() {
        assertEquals(RuntimeClass.castKeepsTheInstance(), vm.invokeStatic(FIX, "castKeepsTheInstance", "()I"))
    }

    @Test fun castToAnUnrelatedTypeThrowsWhereInterpretedCodeCanCatchIt() = string("castToAnUnrelatedType")

    @Test fun aClassNamesTheInterpretedType() {
        string("runtimeName")
        string("simpleName")
        string("classToString")
        string("interfaceToString")
        string("anonymousSimpleName")
    }

    @Test fun anEnumNamesItselfThroughItsPeer() {
        string("enumName")
        string("enumConstantName")
    }
}
