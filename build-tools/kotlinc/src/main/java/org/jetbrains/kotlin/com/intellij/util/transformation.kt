package org.jetbrains.kotlin.com.intellij.util

typealias Transformation<B, R> = (B) -> Collection<R>

typealias XTransformation<B, R> = Transformation<B, XResult<R>>

fun <B, R> xValueTransform(transformation: Transformation<B, R>): XTransformation<B, R> {
    return { baseValue ->
        transformation(baseValue).mapTo(SmartList(), ::ValueResult)
    }
}

fun <B, R> xQueryTransform(subQueries: Transformation<B, Query<out R>>): XTransformation<B, R> {
    return { baseValue ->
        subQueries(baseValue).mapTo(SmartList(), ::QueryResult)
    }
}