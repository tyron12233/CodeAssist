package dev.ide.fakecompose

/**
 * Mirrors OkHttp's `okhttp3.MediaType`: the extension is declared as a MEMBER of the companion object, so it
 * is brought into scope by importing it through the companion (`import okhttp3.MediaType.Companion.toMediaType`)
 * and then called on a plain `String`. That is the canonical OkHttp/Retrofit idiom
 * (`"application/json".toMediaType()`), and the only way to reach the function short of `MediaType.get(...)`.
 *
 * Compiled into the test classpath so the binary (`@kotlin.Metadata`) path is exercised: on the JVM this is a
 * method on `FakeMediaType$Companion` taking the receiver as its first parameter, plus a `@JvmStatic` copy on
 * `FakeMediaType` renamed by `@JvmName`. Only the metadata retains "extension on String declared in the
 * companion".
 */
class FakeMediaType private constructor(val text: String) {

    override fun toString(): String = text

    companion object {
        /** `"application/json".fakeToMediaType()` — an extension on String, declared IN the companion. */
        @JvmStatic
        @JvmName("get")
        fun String.fakeToMediaType(): FakeMediaType = FakeMediaType(this)

        /** The same shape for a nullable receiver, which OkHttp also ships (`toMediaTypeOrNull`). */
        @JvmStatic
        @JvmName("parse")
        fun String.fakeToMediaTypeOrNull(): FakeMediaType? = runCatching { FakeMediaType(this) }.getOrNull()

        /** A plain (non-extension) companion member, for contrast: reached as `FakeMediaType.ANY`. */
        const val ANY: String = "*/*"
    }
}
