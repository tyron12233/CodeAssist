package org.jetbrains.kotlin.com.intellij.util

class XQuery<B, R>(
  private val baseQuery: Query<out B>,
  private val transformation: XTransformation<B, R>
) : AbstractQuery<R>() {

  override fun processResults(consumer: Processor<in R>): Boolean {
    return baseQuery.forEach(Processor { baseValue ->
      for (result: XResult<R> in transformation(baseValue)) {
        if (!result.process(consumer)) {
          return@Processor false
        }
      }
      true
    })
  }
}