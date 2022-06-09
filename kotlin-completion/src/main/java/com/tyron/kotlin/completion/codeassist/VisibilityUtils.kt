package com.tyron.kotlin.completion.codeassist

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.getImplicitReceiversHierarchy

// from idea/idea-core/src/org/jetbrains/kotlin/idea/core/Utils.kt but without the second parameter
fun PsiElement.getResolutionScope(bindingContext: BindingContext): LexicalScope {
    for (parent in parentsWithSelf) {
        if (parent is KtElement) {
            val scope = bindingContext[BindingContext.LEXICAL_SCOPE, parent]
            if (scope != null) return scope
        }

        if (parent is KtClassBody) {
            val classDescriptor = bindingContext[BindingContext.CLASS, parent.getParent()] as? ClassDescriptorWithResolutionScopes
            if (classDescriptor != null) {
                return classDescriptor.getScopeForMemberDeclarationResolution()
            }
        }
    }
    error("Not in JetFile")
}

//from idea/idea-core/src/org/jetbrains/kotlin/idea/core/descriptorUtils.kt
fun DeclarationDescriptorWithVisibility.isVisible(
    from: DeclarationDescriptor,
    bindingContext: BindingContext? = null,
    element: KtSimpleNameExpression? = null
): Boolean {
    if (DescriptorVisibilities.isVisibleWithAnyReceiver(this, from)) return true

    if (bindingContext == null || element == null) return false

    val receiverExpression = element.getReceiverExpression()
    if (receiverExpression != null) {
        val receiverType = bindingContext.getType(receiverExpression) ?: return false
        val explicitReceiver = ExpressionReceiver.create(receiverExpression, receiverType, bindingContext)
        return DescriptorVisibilities.isVisible(explicitReceiver, this, from)
    }
    else {
        val resolutionScope = element.getResolutionScope(bindingContext)
        return resolutionScope.getImplicitReceiversHierarchy().any {
            DescriptorVisibilities.isVisible(it.value, this, from)
        }
    }
}

//from idea/idea-completion/src/org/jetbrains/kotlin/idea/completion/CompletionSession.kt
fun TypeParameterDescriptor.isVisible(where: DeclarationDescriptor?): Boolean {
    val owner = getContainingDeclaration()
    var parent = where
    while (parent != null) {
        if (parent == owner) return true
        if (parent is ClassDescriptor && !parent.isInner()) return false
        parent = parent.getContainingDeclaration()
    }
    return true
}