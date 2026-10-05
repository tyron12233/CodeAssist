package dev.codeassist.ndk.jni

/**
 * A `native` method (Java) or `external fun` (Kotlin), as the JVM will look it up in a native library.
 *
 * [className] is the binary name the JVM uses, with `$` for nesting (`com.example.Outer$Inner`), which is
 * what the JNI symbol is built from. [nameOffset] is where the method's name sits in the source it was read
 * from, so a diagnostic or a gutter mark can point at it.
 */
data class NativeMethod(
    val className: String,
    val name: String,
    val params: List<JvmType>,
    val returnType: JvmType,
    val isStatic: Boolean,
    val nameOffset: Int,
)

/** A JVM type, held as its descriptor (`I`, `[J`, `Ljava/lang/String;`). */
@JvmInline
value class JvmType(val descriptor: String) {
    val isArray: Boolean get() = descriptor.startsWith("[")

    companion object {
        val VOID = JvmType("V")
        val OBJECT = JvmType("Ljava/lang/Object;")
        fun objectType(fqn: String) = JvmType("L${fqn.replace('.', '/')};")
    }
}

/** JNI's naming and typing rules, from the JNI specification's "Resolving Native Method Names". */
object Jni {

    /**
     * The short symbol the JVM tries first: `Java_` + the mangled class + `_` + the mangled method name.
     */
    fun shortName(method: NativeMethod): String =
        "Java_" + mangle(method.className.replace('.', '/')) + "_" + mangle(method.name)

    /**
     * The long symbol, for an overloaded native method: the short one plus `__` and the mangled argument
     * descriptor. The JVM tries it when the short one is absent, so either one implements the method.
     */
    fun longName(method: NativeMethod): String =
        shortName(method) + "__" + mangle(method.params.joinToString("") { it.descriptor })

    /**
     * The symbol to generate for [method] among its class's [siblings]: the long form only when another
     * native method of the class shares the name, since the short one would then be ambiguous.
     */
    fun preferredName(method: NativeMethod, siblings: List<NativeMethod>): String =
        if (siblings.count { it.className == method.className && it.name == method.name } > 1) longName(method)
        else shortName(method)

    /**
     * Escape one name component: `/` separates packages and becomes `_`, and every character that is not an
     * ASCII letter or digit is spelled out (`_1` for `_`, `_2` for `;`, `_3` for `[`, `_0xxxx` for the rest).
     */
    fun mangle(text: String): String = buildString {
        for (c in text) when {
            c == '/' -> append('_')
            c == '_' -> append("_1")
            c == ';' -> append("_2")
            c == '[' -> append("_3")
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> append(c)
            else -> append("_0").append(c.code.toString(16).padStart(4, '0'))
        }
    }

    /** The C type JNI passes [type] as (`jint`, `jstring`, `jobjectArray`, `void`). */
    fun cType(type: JvmType): String = when (val d = type.descriptor) {
        "Z" -> "jboolean"
        "B" -> "jbyte"
        "C" -> "jchar"
        "S" -> "jshort"
        "I" -> "jint"
        "J" -> "jlong"
        "F" -> "jfloat"
        "D" -> "jdouble"
        "V" -> "void"
        "Ljava/lang/String;" -> "jstring"
        "Ljava/lang/Class;" -> "jclass"
        "Ljava/lang/Throwable;" -> "jthrowable"
        else -> when {
            d.startsWith("[") && d.length == 2 -> cType(JvmType(d.substring(1))) + "Array"
            d.startsWith("[") -> "jobjectArray"
            else -> "jobject"
        }
    }

    /**
     * A definition for [method], named [symbol], in C++ or (with [cpp] false) C: `JNIEXPORT … JNICALL` (with
     * `extern "C"` in C++), `JNIEnv*` and the receiver first (`jclass` for a static method, `jobject`
     * otherwise), then one parameter per argument, and a body that returns something of the right type so
     * the stub links as written.
     */
    fun stub(method: NativeMethod, symbol: String, cpp: Boolean = true): String {
        val receiver = if (method.isStatic) "jclass clazz" else "jobject thiz"
        val params = method.params.mapIndexed { i, t -> "${cType(t)} arg$i" }
        val ret = cType(method.returnType)
        val todo = "    // TODO: implement ${method.name}()\n"
        // C reaches the JNI functions through the table (`(*env)->F(env, …)`) and has no nullptr.
        val emptyString = if (cpp) "env->NewStringUTF(\"\")" else "(*env)->NewStringUTF(env, \"\")"
        val body = when (ret) {
            "void" -> todo
            "jboolean" -> "${todo}    return JNI_FALSE;\n"
            "jstring" -> "${todo}    return $emptyString;\n"
            "jint", "jlong", "jbyte", "jchar", "jshort", "jfloat", "jdouble" -> "${todo}    return 0;\n"
            else -> "${todo}    return ${if (cpp) "nullptr" else "NULL"};\n"
        }
        val signature = (listOf("JNIEnv* env", receiver) + params).joinToString(", ")
        val linkage = if (cpp) "extern \"C\" " else ""
        return "${linkage}JNIEXPORT $ret JNICALL\n$symbol($signature) {\n$body}\n"
    }
}
