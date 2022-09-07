package com.tyron.builder.api.extension.impl

import com.tyron.builder.api.artifact.MultipleArtifact
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.VariantSelector
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import org.gradle.api.Action
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * Registrar object to keep track of Variant API operations registered on the [Component]
 */
open class OperationsRegistrar<Component: ComponentIdentity> {

    private class Operation<Component : ComponentIdentity>(
        val selector: VariantSelectorImpl,
        val callBack: Action<Component>
    )

    private val operations = mutableListOf<Operation<Component>>()

    private val noSelector = VariantSelectorImpl().all()
    private val actionsExecuted = AtomicBoolean(false)

    fun addOperation(
        callback: Action<Component>,
        selector: VariantSelector = noSelector
    ) {
        if (actionsExecuted.get()) {
            throw RuntimeException(
                """
                It is too late to add actions as the callbacks already executed.
                Did you try to call beforeVariants or onVariants from the old variant API
                'applicationVariants' for instance ? you should always call beforeVariants or
                onVariants directly from the androidComponents DSL block.
                """
            )
        }
        operations.add(Operation(selector as VariantSelectorImpl, callback))
    }

    fun executeOperations(userVisibleVariant: Component) {
        actionsExecuted.set(true)
        operations.forEach { operation ->
            if (operation.selector.appliesTo(userVisibleVariant)) {
                operation.callBack.execute(userVisibleVariant)
            }
        }
    }
}

class VariantOperationsRegistrar<Component: ComponentIdentity> : OperationsRegistrar<Component>() {

    fun executeOperations(userVisibleVariant: Component, internalVariant: ComponentCreationConfig) {
        super.executeOperations(userVisibleVariant)
        wireAllFinalizedBy(internalVariant)
    }

    private fun wireAllFinalizedBy(variant: ComponentCreationConfig) {
        InternalArtifactType::class.sealedSubclasses.forEach { kClass ->
            handleFinalizedByForType(variant, kClass)
        }
    }

    private fun handleFinalizedByForType(variant: ComponentCreationConfig, type: KClass<out InternalArtifactType<*>>) {
        type.objectInstance?.let { artifact ->
            artifact.finalizingArtifact?.forEach { artifactFinalizedBy ->
                val artifactContainer = when(artifactFinalizedBy) {
                    is SingleArtifact -> variant.artifacts.getArtifactContainer(artifact)
                    is MultipleArtifact -> variant.artifacts.getArtifactContainer(artifact)
                    else -> throw RuntimeException("Unhandled artifact type : $artifactFinalizedBy")
                }
                artifactContainer.getTaskProviders().forEach { taskProvider ->
                    taskProvider.configure {
                        it.finalizedBy(variant.artifacts.get(artifact))
                    }
                }
            }
        }
    }
}
