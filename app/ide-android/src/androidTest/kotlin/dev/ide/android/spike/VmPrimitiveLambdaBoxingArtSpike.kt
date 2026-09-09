package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (ART) validation of the VM's primitive-return `invokedynamic` lambda boxing fix
 * ([dev.ide.jvm.Interpreter]'s `boxLambdaReturn`): a Kotlin `{ false }` compiles to an indy whose impl returns
 * primitive `Z`, adapted to the erased `Supplier.get():Object` SAM. When REAL (bridged) code invokes the
 * proxied lambda — here `java.util.Optional.orElseGet` — the result must box to `java.lang.Boolean`, not
 * `Integer` (the interpreter represents boolean/byte/char/short all as `Int`). This is what made Compose's
 * `MaterialExpressiveTheme` (reading a `CompositionLocal<Boolean>` default factory) crash under the Material3
 * interpret flip. The desktop twin is `KotlinBytecodeTest.primitiveReturningLambdaBoxesToItsOwnWrapperAcross-
 * TheBridge`; this runs the SAME `dev.ide.jvm.kfixtures.KFxKt` bytecode on ART with the on-device
 * [DexPeerFactory] (the SAM proxy is a real dexed class here, not an ASM one), so it exercises the fix on the
 * genuine target.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.VmPrimitiveLambdaBoxingArtSpike
 */
@RunWith(AndroidJUnit4::class)
class VmPrimitiveLambdaBoxingArtSpike {

    private val facade = "dev/ide/jvm/kfixtures/KFxKt"

    private fun newVm(): Vm {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val source = ClassBytesSource { name ->
            if (name.startsWith("dev/ide/jvm/kfixtures/")) {
                runCatching { assets.open("vmbench/${name.substringAfterLast('/')}.class").use { it.readBytes() } }.getOrNull()
            } else null
        }
        return Vm(
            source = source,
            policy = InterpretPolicy { it.startsWith("dev/ide/jvm/kfixtures/") },
            peerFactory = DexPeerFactory(),
        )
    }

    private fun invoked(vm: Vm, name: String): Any? = vm.invokeStatic(facade, name, "()Ljava/lang/Object;", emptyList())

    @Test
    fun primitiveReturningLambdaBoxesToItsOwnWrapperOnArt() {
        val vm = newVm()
        fun typeOf(name: String) = invoked(vm, name)!!.javaClass.name.also { Log.i("VmPrimBoxArt", "$name -> $it") }
        assertEquals("java.lang.Boolean", typeOf("boolViaBridge"))
        assertEquals(false, invoked(vm, "boolViaBridge"))
        assertEquals("java.lang.Byte", typeOf("byteViaBridge"))
        assertEquals(7.toByte(), invoked(vm, "byteViaBridge"))
        assertEquals("java.lang.Character", typeOf("charViaBridge"))
        assertEquals('Q', invoked(vm, "charViaBridge"))
        assertEquals("java.lang.Short", typeOf("shortViaBridge"))
        assertEquals(9.toShort(), invoked(vm, "shortViaBridge"))
        assertEquals("java.lang.Integer", typeOf("intViaBridge")) // control
        assertEquals(42, invoked(vm, "intViaBridge"))
    }
}
