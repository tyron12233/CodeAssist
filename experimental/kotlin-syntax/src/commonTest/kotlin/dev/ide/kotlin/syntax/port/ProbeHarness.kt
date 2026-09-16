package dev.ide.kotlin.syntax.port

import dev.ide.kotlin.syntax.psi.KtElement

/**
 * The non-PSI collaborators the port probe's copied file happens to name.
 *
 * `KotlinControlFlow` asks a resolver for an expression's type in one place. That is `:lang-kotlin`'s own
 * machinery, not anything this module provides, so it is stubbed here rather than counted as a facade gap —
 * the probe is measuring whether PSI-shaped code compiles against the facade, and this is the scaffolding
 * that keeps the question clean.
 */
internal class KotlinResolver {
    fun inferType(expression: KtElement): InferredType? = null
}

internal class InferredType(val qualifiedName: String?)
