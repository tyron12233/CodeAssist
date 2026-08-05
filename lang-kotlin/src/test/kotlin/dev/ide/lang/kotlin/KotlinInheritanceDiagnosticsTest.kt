package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Inheritance / override correctness diagnostics: a concrete class must implement inherited abstract members
 * (`kt.abstractNotImplemented`), an `override` must override something (`kt.nothingToOverride`), a member
 * hiding an inherited one needs `override` (`kt.overrideRequired`), and an interface / abstract class cannot
 * be instantiated (`kt.abstractInstantiation`) — plus the "Implement members" quick-fix. Each error case
 * fires; each valid counterpart and each conservative back-off (unresolved supertype, default method,
 * delegation, factory function, companion `invoke`) does NOT — the parse-only model must never false-positive.
 */
class KotlinInheritanceDiagnosticsTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun codes(fileName: String, code: String) = diagnose(fileName, code).map { it.code }

    // --- abstract member not implemented ---

    @Test
    fun concreteClassMissingAbstractMemberIsFlagged() {
        val diags = diagnose("Dog.kt", "package demo\nclass Dog : Animal")
        val d = diags.firstOrNull { it.code == "kt.abstractNotImplemented" }
        assertNotNull(d, "a concrete class leaving `sound` unimplemented should be flagged; got $diags")
        assertTrue(d.message.contains("sound"), "message names the missing member; got '${d.message}'")
    }

    @Test
    fun implementingTheAbstractMemberClearsTheError() {
        val diags = diagnose("Dog.kt", "package demo\nclass Dog : Animal { override fun sound(): String = \"woof\" }")
        assertFalse(diags.any { it.code == "kt.abstractNotImplemented" }, "implemented → no error; got $diags")
    }

    @Test
    fun abstractPropertyImplementedByConstructorParameter() {
        // A primary-ctor `val name` implements the interface's abstract `val name`.
        assertFalse(
            "kt.abstractNotImplemented" in codes("P.kt", "package demo\nclass Person(override val name: String) : Named"),
            "a ctor `val name` implements the abstract property",
        )
        assertTrue(
            "kt.abstractNotImplemented" in codes("P2.kt", "package demo\nclass Person2 : Named"),
            "not implementing the abstract property is flagged",
        )
    }

    @Test
    fun interfaceDefaultMethodIsNotRequired() {
        // Clickable.onClick is abstract; onLongClick has a default body → only onClick is required.
        val d = diagnose("C.kt", "package demo\nclass C : Clickable { override fun onClick() {} }")
        assertFalse(d.any { it.code == "kt.abstractNotImplemented" }, "default method must not be required; got $d")
    }

    @Test
    fun abstractClassNeedNotImplementInheritedAbstracts() {
        assertFalse(
            "kt.abstractNotImplemented" in codes("X.kt", "package demo\nabstract class X : Animal"),
            "an abstract class may leave abstract members unimplemented",
        )
    }

    @Test
    fun classWithNoAbstractMembersInheritedIsNotFlagged() {
        // Base has only concrete (open/final) members → nothing to implement.
        assertFalse("kt.abstractNotImplemented" in codes("D.kt", "package demo\nclass D : Base"), "no abstracts → no error")
    }

    @Test
    fun unresolvedSupertypeBacksOff() {
        // `Mystery` doesn't resolve → we can't see the whole picture, so emit nothing (conservative).
        assertFalse("kt.abstractNotImplemented" in codes("U.kt", "package demo\nclass U : Mystery"), "unresolved super → back off")
    }

    // --- nothing to override ---

    @Test
    fun overrideOfNonexistentMemberIsFlagged() {
        assertTrue(
            "kt.nothingToOverride" in codes("N.kt", "package demo\nclass N : Base { override fun ghost() {} }"),
            "`override` of a member no supertype declares should be flagged",
        )
    }

    @Test
    fun genuineOverrideIsNotFlaggedAsNothingToOverride() {
        assertFalse(
            "kt.nothingToOverride" in codes("R.kt", "package demo\nclass R : Base { override fun render(): String = \"x\" }"),
            "a real override must not be flagged",
        )
    }

    // --- override required ---

    @Test
    fun hidingMemberWithoutOverrideIsFlagged() {
        assertTrue(
            "kt.overrideRequired" in codes("H.kt", "package demo\nclass H : Base { fun render(): String = \"x\" }"),
            "a member hiding an inherited one without `override` should be flagged",
        )
    }

    @Test
    fun newOverloadIsNotFlaggedAsOverrideRequired() {
        // render(Int) is a NEW overload of the inherited render() (arity differs) — not an override.
        assertFalse(
            "kt.overrideRequired" in codes("O.kt", "package demo\nclass O : Base { fun render(x: Int) {} }"),
            "a genuine overload must not require `override`",
        )
    }

    // --- abstract instantiation ---

    @Test
    fun instantiatingAbstractClassIsFlagged() {
        assertTrue("kt.abstractInstantiation" in codes("IA.kt", "package demo\nval a = Animal()"), "`Animal()` should be flagged")
    }

    @Test
    fun instantiatingInterfaceIsFlagged() {
        assertTrue("kt.abstractInstantiation" in codes("II.kt", "package demo\nval n = Named()"), "`Named()` should be flagged")
    }

    @Test
    fun instantiatingConcreteClassIsNotFlagged() {
        assertFalse("kt.abstractInstantiation" in codes("IC.kt", "package demo\nval b = Base()"), "a concrete class is instantiable")
    }

    @Test
    fun factoryFunctionSharingTypeNameIsNotFlagged() {
        // `Producer()` resolves the FUNCTION (a factory), not the interface constructor — must not be flagged.
        assertFalse(
            "kt.abstractInstantiation" in codes("F.kt", "package demo\nval p = Producer()"),
            "a same-named factory function must suppress the abstract-instantiation error",
        )
    }

    @Test
    fun samConversionWithTrailingLambdaIsNotFlagged() {
        // `Action { }` is a SAM conversion of a functional interface — valid Kotlin, not a forbidden constructor.
        assertFalse(
            "kt.abstractInstantiation" in codes("S.kt", "package demo\nval a = Action { }"),
            "a SAM conversion `Action { }` must not be flagged as abstract instantiation",
        )
    }

    @Test
    fun samConversionWithCallableReferenceIsNotFlagged() {
        assertFalse(
            "kt.abstractInstantiation" in codes("SR.kt", "package demo\nval a = Action(::doRun)"),
            "a SAM conversion from a callable reference `Action(::doRun)` must not be flagged",
        )
    }

    @Test
    fun functionalInterfaceNoArgConstructionIsStillFlagged() {
        // No functional argument → not a SAM conversion; a bare `Action()` is still an illegal interface call.
        assertTrue(
            "kt.abstractInstantiation" in codes("SN.kt", "package demo\nval a = Action()"),
            "`Action()` with no functional argument is not a SAM conversion and should be flagged",
        )
    }

    // --- final supertype (Kotlin classes are final by default) ---

    @Test
    fun extendingFinalClassWithConstructorCallIsFlagged() {
        // The reported bug: `class Cow(...) : Animal2()` where Animal2 is a plain final class.
        val d = diagnose("Cow.kt", "package demo\nclass Cow(val n: Int = 0) : Animal2()")
        val err = d.firstOrNull { it.code == "kt.finalSupertype" }
        assertNotNull(err, "extending a final class should be flagged; got $d")
        assertTrue(err.message.contains("final"), "message mentions finality; got '${err.message}'")
    }

    @Test
    fun extendingFinalClassBareFormIsFlaggedOnceNotAsUninitialized() {
        // Bare form `: Animal2` — the final error subsumes the "must be initialized" one (matches kotlinc).
        val d = codes("CowBare.kt", "package demo\nclass CowBare : Animal2")
        assertTrue("kt.finalSupertype" in d, "bare final supertype is flagged; got $d")
        assertFalse("kt.supertypeNotInitialized" in d, "a final supertype must not ALSO be flagged uninitialized; got $d")
    }

    @Test
    fun extendingFinalDataClassIsFlagged() {
        assertTrue(
            "kt.finalSupertype" in codes("SubData.kt", "package demo\nclass SubData : Box()"),
            "a data class is final and cannot be extended",
        )
    }

    @Test
    fun objectExtendingFinalClassIsFlagged() {
        assertTrue(
            "kt.finalSupertype" in codes("Obj.kt", "package demo\nobject Obj : Animal2()"),
            "an object extending a final class is still FINAL_SUPERTYPE",
        )
    }

    @Test
    fun extendingOpenClassIsNotFlagged() {
        assertFalse(
            "kt.finalSupertype" in codes("SubBase.kt", "package demo\nclass SubBase : Base()"),
            "an `open` class may be extended",
        )
    }

    @Test
    fun extendingAbstractClassIsNotFlagged() {
        assertFalse(
            "kt.finalSupertype" in codes("SubAnimal.kt", "package demo\nclass SubAnimal : Animal() { override fun sound() = \"x\" }"),
            "an abstract class may be extended",
        )
    }

    @Test
    fun implementingInterfaceIsNotFlagged() {
        assertFalse(
            "kt.finalSupertype" in codes("Impl.kt", "package demo\nclass Impl : Named { override val name = \"x\" }"),
            "an interface is not a final class — implementing it is fine",
        )
    }

    @Test
    fun unresolvedFinalSupertypeBacksOff() {
        assertFalse(
            "kt.finalSupertype" in codes("Unk.kt", "package demo\nclass Unk : Mystery()"),
            "an unresolved supertype must back off (no false positive)",
        )
    }

    @Test
    fun extendingFinalKotlinBuiltinIsFlagged() {
        // The closed gap: a final Kotlin built-in (`String`) resolved via `.kotlin_builtins`, not project source.
        assertTrue(
            "kt.finalSupertype" in codes("SubStr.kt", "package demo\nclass SubStr : String()"),
            "extending the final built-in `String` should be flagged",
        )
    }

    @Test
    fun implementingBuiltinInterfaceIsNotFlagged() {
        // A built-in INTERFACE (`Comparable`) is not a final class — implementing it must not be flagged.
        assertFalse(
            "kt.finalSupertype" in codes("Cmp.kt", "package demo\nclass Cmp : Comparable<Int> { override fun compareTo(other: Int) = 0 }"),
            "a built-in interface is not a final class",
        )
    }

    // --- implement-members quick-fix ---

    @Test
    fun implementMembersFixGeneratesOverrideStub() {
        val code = "package demo\nclass Dog : Animal"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("DogFix.kt")))
        val fix = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.implementMembersFix(doc.file, code.indexOf("Dog"))
        }
        assertNotNull(fix, "a fix should be offered for the unimplemented class")
        val inserted = fix.edits.joinToString("") { it.newText }
        assertTrue(inserted.contains("override fun sound(): String"), "stub overrides `sound`; got '$inserted'")
        assertTrue(inserted.contains("TODO("), "stub body is a TODO; got '$inserted'")
    }

    // --- @Parcelize: the kotlin-parcelize compiler plugin supplies Parcelable's members ---

    @Test
    fun parcelizeClassIsNotFlaggedForParcelableMembers() {
        // The plugin generates writeToParcel()/describeContents() (+ CREATOR) at build time; the parse-only
        // editor can't see them, so a @Parcelize class must NOT be flagged for them (the reported bug).
        val diags = codes("AssetSource.kt", "package demo\n@Parcelize class AssetSource(val paths: List<String>) : Parcelable")
        assertFalse("kt.abstractNotImplemented" in diags, "a @Parcelize class must not be flagged for Parcelable members; got $diags")
    }

    @Test
    fun withoutParcelizeTheSameClassIsStillFlagged() {
        // Negative control: no @Parcelize → the Parcelable abstracts are genuinely unimplemented.
        val d = diagnose("Plain.kt", "package demo\nclass Plain : Parcelable")
        val err = d.firstOrNull { it.code == "kt.abstractNotImplemented" }
        assertNotNull(err, "without @Parcelize the Parcelable members are unimplemented; got $d")
        assertTrue(
            err.message.contains("writeToParcel") && err.message.contains("describeContents"),
            "the control names both Parcelable members; got '${err.message}'",
        )
    }

    @Test
    fun parcelizeStillFlagsUnrelatedMissingMembers() {
        // Only Parcelable's members are excused; an unrelated abstract (Named.name) is still required.
        val d = diagnose("Half.kt", "package demo\n@Parcelize class Half(val paths: List<String>) : Parcelable, Named")
        val err = d.firstOrNull { it.code == "kt.abstractNotImplemented" }
        assertNotNull(err, "an unrelated abstract member must still be flagged; got $d")
        assertTrue(err.message.contains("name"), "names the genuinely-missing member; got '${err.message}'")
        assertFalse(err.message.contains("writeToParcel"), "must not name the plugin-supplied member; got '${err.message}'")
    }

    @Test
    fun parcelizeClassOffersNoImplementFixForParcelableMembers() {
        val code = "package demo\n@Parcelize class NoFix(val paths: List<String>) : Parcelable"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("NoFix.kt")))
        val fix = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.implementMembersFix(doc.file, code.indexOf("NoFix"))
        }
        assertTrue(fix == null || fix.edits.isEmpty(), "no members to implement for a @Parcelize class; got $fix")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Bases.kt" to """
                    package demo
                    interface Named { val name: String }
                    abstract class Animal { abstract fun sound(): String; open fun legs(): Int = 4 }
                    class Animal2
                    data class Box(val n: Int = 0)
                    interface Clickable { fun onClick(); fun onLongClick(): Boolean = false }
                    open class Base { open fun render(): String = ""; fun fixed() {} }
                    interface Producer
                    fun Producer(): Producer = object : Producer {}
                    fun interface Action { fun run() }
                    fun doRun() {}
                    class Parcel
                    interface Parcelable { fun writeToParcel(dest: Parcel, flags: Int); fun describeContents(): Int }
                    annotation class Parcelize
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
