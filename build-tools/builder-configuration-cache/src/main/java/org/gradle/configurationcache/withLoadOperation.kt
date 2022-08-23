package org.gradle.configurationcache

import org.gradle.internal.operations.BuildOperationContext
import org.gradle.internal.operations.BuildOperationDescriptor
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.operations.CallableBuildOperation


internal
fun <T : Any> BuildOperationExecutor.withLoadOperation(block: () -> T) =
    withOperation("Load configuration cache state", block)


internal
fun BuildOperationExecutor.withStoreOperation(block: () -> Unit) =
    withOperation("Store configuration cache state", block)


private
fun <T : Any> BuildOperationExecutor.withOperation(displayName: String, block: () -> T): T =
    call(object : CallableBuildOperation<T> {
        override fun description() = BuildOperationDescriptor.displayName(displayName)
        override fun call(context: BuildOperationContext) = block()
    })
