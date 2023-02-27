package org.jetbrains.kotlin.com.intellij.util

/**
 * Value of type [X] or [Query] of values of type [X].
 */
sealed class XResult<out X> {

    abstract fun process(processor: Processor<in X>): Boolean

    abstract fun <R> transform(transformation: XTransformation<X, R>): Collection<XResult<R>>
}

internal class ValueResult<X>(val value: X) : XResult<X>() {

    override fun process(processor: Processor<in X>): Boolean {
        return processor.process(value)
    }

    override fun <R> transform(transformation: XTransformation<X, R>): Collection<XResult<R>> {
        return transformation(value)
    }
}

internal class QueryResult<X>(val query: Query<out X>) : XResult<X>() {

    override fun process(processor: Processor<in X>): Boolean {
        return query.forEach(processor)
    }

    override fun <R> transform(transformation: XTransformation<X, R>): Collection<XResult<R>> {
        return listOf(QueryResult(XQuery(query, transformation)))
    }
}