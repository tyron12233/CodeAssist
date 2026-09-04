package dev.ide.jvm

import dev.ide.jvm.fixtures.Enums
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Interpreted enums crossing to platform code. `EnumSet`/`EnumMap`/`Enum.valueOf` are written against the real
 * enum contract — `Class.isEnum()`, a `values()` universe indexed by ordinal, `getDeclaringClass()` — so the
 * peer for an interpreted enum has to be a real enum itself, one class shared by all of that enum's constants.
 * Every case's oracle is the same call made for real.
 */
class VmEnumPeerTest {

    private val vm = Vm()

    private val ENUMS = "dev/ide/jvm/fixtures/Enums"

    private fun call(name: String, descriptor: String): Any? = vm.invokeStatic(ENUMS, name, descriptor)

    /** A `boolean` result comes back in the interpreter's computational form (0/1). */
    private fun callBoolean(name: String): Boolean = call(name, "()Z") as Int != 0

    // ---- enum instances handed to real code -------------------------------------------------------

    @Test fun enumSetOfConstants() {
        assertEquals(Enums.setOfTwo(), call("setOfTwo", "()I"))
    }

    @Test fun enumSetMembership() {
        assertEquals(Enums.setContainsHigh(), callBoolean("setContainsHigh"))
        assertEquals(Enums.setContainsMedium(), callBoolean("setContainsMedium"))
    }

    @Test fun enumSetIteratesInOrdinalOrder() {
        assertEquals(Enums.setText(), call("setText", "()Ljava/lang/String;"))
    }

    @Test fun enumMapKeyedByConstants() {
        assertEquals(Enums.mapText(), call("mapText", "()Ljava/lang/String;"))
    }

    // ---- the enum's class literal handed to real code ---------------------------------------------

    @Test fun enumSetAllOfClassLiteral() {
        assertEquals(Enums.allOfSize(), call("allOfSize", "()I"))
        assertEquals(Enums.allOfText(), call("allOfText", "()Ljava/lang/String;"))
    }

    @Test fun classLiteralReportsTheEnumConstants() {
        assertEquals(Enums.constantCount(), call("constantCount", "()I"))
        assertEquals(Enums.classLiteralIsEnum(), callBoolean("classLiteralIsEnum"))
    }

    @Test fun classLiteralIsTheConstantsOwnClass() {
        assertEquals(Enums.getClassMatchesClassLiteral(), callBoolean("getClassMatchesClassLiteral"))
    }

    @Test fun reflectiveValueOf() {
        assertEquals(Enums.valueOfReflectively(), call("valueOfReflectively", "()Ljava/lang/String;"))
        assertEquals(Enums.valueOfKeepsIdentity(), callBoolean("valueOfKeepsIdentity"))
    }

    // ---- the state Enum's own (final) methods read off the peer -----------------------------------

    @Test fun nameOrdinalAndCompare() {
        assertEquals(Enums.ordinalOfHigh(), call("ordinalOfHigh", "()I"))
        assertEquals(Enums.nameOfMedium(), call("nameOfMedium", "()Ljava/lang/String;"))
        assertEquals(Enums.compareLowToHigh(), call("compareLowToHigh", "()I"))
    }

    // ---- constant bodies, reached through a real interface ----------------------------------------

    @Test fun constantBodyThroughRealInterface() {
        assertEquals(Enums.reduceWithTimes(), call("reduceWithTimes", "()I"))
        assertEquals(Enums.reduceWithPlus(), call("reduceWithPlus", "()I"))
    }

    @Test fun constantBodiesShareOneEnumUniverse() {
        assertEquals(Enums.opText(), call("opText", "()Ljava/lang/String;"))
    }
}
