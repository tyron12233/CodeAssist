package dev.codeassist.ndk.jni

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JniTest {

    private fun method(cls: String, name: String, vararg params: String, ret: String = "V", static: Boolean = false) =
        NativeMethod(cls, name, params.map(::JvmType), JvmType(ret), static, 0)

    @Test
    fun shortNameMatchesTheJniSpecification() {
        assertEquals(
            "Java_com_example_myapp_MainActivity_stringFromJNI",
            Jni.shortName(method("com.example.myapp.MainActivity", "stringFromJNI")),
        )
    }

    @Test
    fun underscoresAndNestedClassesAreEscaped() {
        assertEquals("Java_com_my_1app_Outer_00024Inner_do_1it", Jni.shortName(method("com.my_app.Outer\$Inner", "do_it")))
    }

    @Test
    fun longNameAppendsTheMangledArgumentDescriptor() {
        val m = method("p.C", "f", "I", "Ljava/lang/String;", "[J")
        assertEquals("Java_p_C_f__ILjava_lang_String_2_3J", Jni.longName(m))
    }

    @Test
    fun theLongFormIsPreferredOnlyForOverloads() {
        val a = method("p.C", "f", "I")
        val b = method("p.C", "f", "J")
        val c = method("p.C", "g")
        assertEquals(Jni.longName(a), Jni.preferredName(a, listOf(a, b, c)))
        assertEquals(Jni.shortName(c), Jni.preferredName(c, listOf(a, b, c)))
    }

    @Test
    fun nonAsciiIsSpelledAsFourHexDigits() {
        assertEquals("caf_000e9", Jni.mangle("café"))
    }

    @Test
    fun cTypesFollowTheDescriptor() {
        assertEquals("jint", Jni.cType(JvmType("I")))
        assertEquals("jstring", Jni.cType(JvmType("Ljava/lang/String;")))
        assertEquals("jbyteArray", Jni.cType(JvmType("[B")))
        assertEquals("jobjectArray", Jni.cType(JvmType("[Ljava/lang/String;")))
        assertEquals("jobjectArray", Jni.cType(JvmType("[[I")))
        assertEquals("jobject", Jni.cType(JvmType("Landroid/graphics/Bitmap;")))
        assertEquals("jclass", Jni.cType(JvmType("Ljava/lang/Class;")))
    }

    @Test
    fun aStubForAStaticMethodTakesTheClassAndReturnsTheRightType() {
        val stub = Jni.stub(method("p.C", "sum", "I", "I", ret = "I", static = true), "Java_p_C_sum")
        assertTrue(stub.startsWith("extern \"C\" JNIEXPORT jint JNICALL\nJava_p_C_sum(JNIEnv* env, jclass clazz, jint arg0, jint arg1) {"), stub)
        assertTrue("return 0;" in stub)
    }

    @Test
    fun aCStubUsesTheFunctionTableAndNoExternC() {
        val stub = Jni.stub(method("p.C", "name", ret = "Ljava/lang/String;"), "Java_p_C_name", cpp = false)
        assertFalse("extern \"C\"" in stub)
        assertTrue("(*env)->NewStringUTF(env, \"\")" in stub, stub)
        assertTrue("jobject thiz" in stub)
    }
}

class NativeMethodScannerTest {

    @Test
    fun javaNativesWithTheirClassStaticnessAndTypes() {
        val src = """
            package com.example;

            import android.graphics.Bitmap;

            /** native in a comment: public native void notThis(); */
            public class MainActivity {
                static { System.loadLibrary("native-lib"); }
                private String label = "native int fake();";

                public native String stringFromJNI();
                private static native int sum(int a, final int b);
                native void fill(@NonNull Bitmap bitmap, byte[] pixels, String... names);

                static class Worker {
                    native long run(long handle);
                }

                void local() {
                    Runnable r = new Runnable() { public void run() {} };
                }
            }
        """.trimIndent()
        val found = NativeMethodScanner.scanJava(src)
        assertEquals(listOf("stringFromJNI", "sum", "fill", "run"), found.map { it.name })

        val (s, sum, fill, run) = found
        assertEquals("com.example.MainActivity", s.className)
        assertEquals("Ljava/lang/String;", s.returnType.descriptor)
        assertFalse(s.isStatic)
        assertTrue(sum.isStatic)
        assertEquals(listOf("I", "I"), sum.params.map { it.descriptor })
        assertEquals(listOf("Landroid/graphics/Bitmap;", "[B", "[Ljava/lang/String;"), fill.params.map { it.descriptor })
        assertEquals("com.example.MainActivity\$Worker", run.className)
        assertEquals(src.indexOf("stringFromJNI"), s.nameOffset)
    }

    @Test
    fun kotlinExternalsInAClassACompanionAnObjectAndAtTopLevel() {
        val src = """
            @file:JvmName("NativeLib")
            package com.example

            import android.graphics.Bitmap

            class MainActivity : Activity() {
                external fun stringFromJNI(): String
                external fun sum(a: Int, b: Int = 1): Int
                external fun fill(bitmap: Bitmap?, pixels: ByteArray, vararg names: String)

                companion object {
                    init { System.loadLibrary("native-lib") }
                    @JvmStatic external fun version(): Long
                    external fun companionOnly(values: Array<Int>): Boolean
                }
            }

            object Engine {
                external fun start(handle: Long)
                @JvmStatic external fun stop()
            }

            data class Point(val x: Int, val y: Int)

            external fun topLevel(text: String?): IntArray
        """.trimIndent()
        val found = NativeMethodScanner.scanKotlin(src, "Native.kt").associateBy { it.name }
        assertEquals(setOf("stringFromJNI", "sum", "fill", "version", "companionOnly", "start", "stop", "topLevel"), found.keys)

        assertEquals("com.example.MainActivity", found.getValue("stringFromJNI").className)
        assertFalse(found.getValue("stringFromJNI").isStatic)
        assertEquals("Ljava/lang/String;", found.getValue("stringFromJNI").returnType.descriptor)
        assertEquals(listOf("I", "I"), found.getValue("sum").params.map { it.descriptor })
        assertEquals(listOf("Landroid/graphics/Bitmap;", "[B", "[Ljava/lang/String;"), found.getValue("fill").params.map { it.descriptor })
        assertEquals("V", found.getValue("fill").returnType.descriptor)

        // @JvmStatic in a companion: a static native on the enclosing class.
        assertEquals("com.example.MainActivity", found.getValue("version").className)
        assertTrue(found.getValue("version").isStatic)
        // Without it, an instance method of the companion class, with boxed array elements.
        assertEquals("com.example.MainActivity\$Companion", found.getValue("companionOnly").className)
        assertFalse(found.getValue("companionOnly").isStatic)
        assertEquals(listOf("[Ljava/lang/Integer;"), found.getValue("companionOnly").params.map { it.descriptor })

        assertEquals("com.example.Engine", found.getValue("start").className)
        assertFalse(found.getValue("start").isStatic)
        assertTrue(found.getValue("stop").isStatic)

        assertEquals("com.example.NativeLib", found.getValue("topLevel").className)
        assertTrue(found.getValue("topLevel").isStatic)
        assertEquals("[I", found.getValue("topLevel").returnType.descriptor)
    }

    @Test
    fun aTopLevelFunctionWithoutJvmNameLivesOnTheFileFacade() {
        val found = NativeMethodScanner.scanKotlin("package p\n\nexternal fun f()\n", "native-bridge.kt")
        assertEquals("p.Native-bridgeKt", found.single().className)
    }

    @Test
    fun aBodylessClassDoesNotClaimTheNextBrace() {
        val src = "package p\nclass Holder(val x: Int)\nfun helper() { }\nclass Real {\n    external fun f()\n}\n"
        assertEquals("p.Real", NativeMethodScanner.scanKotlin(src, "A.kt").single().className)
    }
}

class CppJniScannerTest {

    @Test
    fun definitionsCountAndDeclarationsDoNot() {
        val src = """
            #include <jni.h>
            // Java_p_C_commented(JNIEnv*, jobject) {}
            extern "C" JNIEXPORT jstring JNICALL Java_p_C_declared(JNIEnv*, jobject);

            extern "C" JNIEXPORT jstring JNICALL
            Java_p_C_defined(JNIEnv* env, jobject /* this */) {
                return env->NewStringUTF("Java_p_C_inString(){}");
            }

            JNIEXPORT jint JNICALL Java_p_C_sum__II(JNIEnv *env, jclass clazz, jint a, jint b) noexcept { return a + b; }
        """.trimIndent()
        val file = CppJniScanner.scan(src)
        assertEquals(listOf("Java_p_C_defined", "Java_p_C_sum__II"), file.functions.map { it.name })
        assertEquals(src.indexOf("Java_p_C_defined"), file.functions.first().nameOffset)
        assertFalse(file.registersNatives)
    }

    @Test
    fun registerNativesIsNoticed() {
        val src = "jint JNI_OnLoad(JavaVM* vm, void*) { env->RegisterNatives(cls, methods, 2); return JNI_VERSION_1_6; }"
        assertTrue(CppJniScanner.scan(src).registersNatives)
    }
}
