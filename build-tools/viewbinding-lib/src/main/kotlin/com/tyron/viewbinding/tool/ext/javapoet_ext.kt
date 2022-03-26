package com.tyron.viewbinding.tool.ext

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec

const val L = "\$L"
const val T = "\$T"
const val N = "\$N"
const val S = "\$S"
const val W = "\$W"

fun javaFile(
    packageName: String,
    typeSpec: TypeSpec,
    body: JavaFile.Builder.() -> Unit
): JavaFile = JavaFile.builder(packageName, typeSpec).apply(body).build()

fun classSpec(
    name: ClassName,
    body: TypeSpec.Builder.() -> Unit = {}
): TypeSpec = TypeSpec.classBuilder(name).apply(body).build()

fun constructorSpec(
    body: MethodSpec.Builder.() -> Unit = {}
): MethodSpec = MethodSpec.constructorBuilder().apply(body).build()

fun methodSpec(
    name: String,
    body: MethodSpec.Builder.() -> Unit = {}
): MethodSpec = MethodSpec.methodBuilder(name).apply(body).build()

fun parameterSpec(
    type: TypeName,
    name: String,
    body: ParameterSpec.Builder.() -> Unit = {}
): ParameterSpec = ParameterSpec.builder(type, name).apply(body).build()

fun fieldSpec(
    name: String,
    type: TypeName,
    body: FieldSpec.Builder.() -> Unit = {}
): FieldSpec = FieldSpec.builder(type, name).apply(body).build()

fun String.toClassName(): ClassName = ClassName.bestGuess(this)

/**
 * Try to parse [value] as a qualified layout XML class name. Unlike normal qualified class name
 * references, nested classes are separated by dollar signs ('$') instead of periods ('.').
 *
 * @throws IllegalArgumentException if [value] fails to parse
 */
fun parseLayoutClassName(value: String, filename: String): ClassName {
    return try {
        val lastDot = value.lastIndexOf('.')
        val packageName = if (lastDot == -1) "" else value.substring(0, lastDot)
        val simpleNames = value.substring(lastDot + 1).split('$')
        ClassName.get(packageName, simpleNames.first(), *simpleNames.drop(1).toTypedArray())
    } catch (e: Exception) {
        throw IllegalArgumentException("Unable to parse \"$value\" as class in $filename.xml", e)
    }
}