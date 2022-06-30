package com.tyron.viewbinding.tool

import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth.assertAbout
import com.google.common.truth.Truth.assertThat
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourceSubjectFactory
import com.squareup.javapoet.JavaFile

fun JavaFile.assert(assertions: JavaFileSubject.() -> Unit) {
    assertAbout(::JavaFileSubject).that(this).apply(assertions)
}

class JavaFileSubject(
    metadata: FailureMetadata,
    actual: JavaFile
) : Subject/*<JavaFileSubject, JavaFile>*/(metadata, actual) {
    private val actualString by lazy(actual::toString)

    fun contains(expected: String) {
        assertThat(actualString).contains(expected)
    }

    fun doesNotContain(expected: String) {
        assertThat(actualString).doesNotContain(expected)
    }

    fun parsesAs(expected: String) {
        assertAbout(JavaSourceSubjectFactory.javaSource())
            .that(JavaFileObjects.forSourceString("Actual", actualString /*todo: actual().toString() ???*/))
            .parsesAs(JavaFileObjects.forSourceString("Expected", expected))
    }
}
