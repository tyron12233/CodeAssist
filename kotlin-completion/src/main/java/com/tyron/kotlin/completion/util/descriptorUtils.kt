package com.tyron.kotlin.completion.util

import com.tyron.kotlin.completion.codeInsight.DescriptorToSourceUtilsIde
import com.tyron.kotlin.completion.resolve.ResolutionFacade
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.resolve.findOriginalTopMostOverriddenDescriptors
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.getImplicitReceiversHierarchy
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor

fun DeclarationDescriptorWithVisibility.isVisible(from: DeclarationDescriptor): Boolean {
    return isVisible(from, null)
}

fun DeclarationDescriptorWithVisibility.isVisible(
    context: PsiElement,
    receiverExpression: KtExpression?,
    bindingContext: BindingContext,
    resolutionFacade: ResolutionFacade
): Boolean {
    val resolutionScope = context.getResolutionScope(bindingContext, resolutionFacade)
    val from = resolutionScope.ownerDescriptor
    return isVisible(from, receiverExpression, bindingContext, resolutionScope)
}

private fun DeclarationDescriptorWithVisibility.isVisible(
    from: DeclarationDescriptor,
    receiverExpression: KtExpression?,
    bindingContext: BindingContext? = null,
    resolutionScope: LexicalScope? = null
): Boolean {
    if (DescriptorVisibilities.isVisibleWithAnyReceiver(this, from, false)) return true

    if (bindingContext == null || resolutionScope == null) return false

    // for extension it makes no sense to check explicit receiver because we need dispatch receiver which is implicit in this case
    if (receiverExpression != null && !isExtension) {
        val receiverType = bindingContext.getType(receiverExpression) ?: return false
        val explicitReceiver = ExpressionReceiver.create(receiverExpression, receiverType, bindingContext)
        return DescriptorVisibilities.isVisible(explicitReceiver, this, from, false)
    } else {
        return resolutionScope.getImplicitReceiversHierarchy().any {
            DescriptorVisibilities.isVisible(it.value, this, from, false)
        }
    }
}

private fun compareDescriptorsText(project: Project, d1: DeclarationDescriptor, d2: DeclarationDescriptor): Boolean {
    if (d1 == d2) return true
    if (d1.name != d2.name) return false

    val renderedD1 = IdeDescriptorRenderersScripting.SOURCE_CODE.render(d1)
    val renderedD2 = IdeDescriptorRenderersScripting.SOURCE_CODE.render(d2)
    if (renderedD1 == renderedD2) return true

    val declarations1 = DescriptorToSourceUtilsIde.getAllDeclarations(project, d1)
    val declarations2 = DescriptorToSourceUtilsIde.getAllDeclarations(project, d2)
    if (declarations1 == declarations2 && declarations1.isNotEmpty()) return true

    return false
}

fun compareDescriptors(project: Project, currentDescriptor: DeclarationDescriptor?, originalDescriptor: DeclarationDescriptor?): Boolean {
    if (currentDescriptor == originalDescriptor) return true
    if (currentDescriptor == null || originalDescriptor == null) return false

    if (currentDescriptor.name != originalDescriptor.name) return false

    if (originalDescriptor is SyntheticJavaPropertyDescriptor && currentDescriptor is SyntheticJavaPropertyDescriptor) {
        return compareDescriptors(project, currentDescriptor.getMethod, originalDescriptor.getMethod)
    }

    if (compareDescriptorsText(project, currentDescriptor, originalDescriptor)) return true

    if (originalDescriptor is CallableDescriptor && currentDescriptor is CallableDescriptor) {
        val overriddenOriginalDescriptor = originalDescriptor.findOriginalTopMostOverriddenDescriptors()
        val overriddenCurrentDescriptor = currentDescriptor.findOriginalTopMostOverriddenDescriptors()

        if (overriddenOriginalDescriptor.size != overriddenCurrentDescriptor.size) return false
        return overriddenCurrentDescriptor.zip(overriddenOriginalDescriptor).all {
            compareDescriptorsText(project, it.first, it.second)
        }
    }

    return false
}

fun DescriptorVisibility.toKeywordToken(): KtModifierKeywordToken = when (val normalized = normalize()) {
    DescriptorVisibilities.PUBLIC -> KtTokens.PUBLIC_KEYWORD
    DescriptorVisibilities.PROTECTED -> KtTokens.PROTECTED_KEYWORD
    DescriptorVisibilities.INTERNAL -> KtTokens.INTERNAL_KEYWORD
    else -> {
        if (DescriptorVisibilities.isPrivate(normalized)) {
            KtTokens.PRIVATE_KEYWORD
        } else {
            error("Unexpected visibility '$normalized'")
        }
    }
}

fun <D : CallableMemberDescriptor> D.getDirectlyOverriddenDeclarations(): Collection<D> {
    val result = LinkedHashSet<D>()
    for (overriddenDescriptor in overriddenDescriptors) {
        @Suppress("UNCHECKED_CAST")
        when (overriddenDescriptor.kind) {
            CallableMemberDescriptor.Kind.DECLARATION -> result.add(overriddenDescriptor as D)
            CallableMemberDescriptor.Kind.FAKE_OVERRIDE, CallableMemberDescriptor.Kind.DELEGATION -> result.addAll((overriddenDescriptor as D).getDirectlyOverriddenDeclarations())
            CallableMemberDescriptor.Kind.SYNTHESIZED -> {
                //do nothing
            }
            else -> throw AssertionError("Unexpected callable kind ${overriddenDescriptor.kind}: $overriddenDescriptor")
        }
    }
    return OverridingUtil.filterOutOverridden(result)
}

fun <D : CallableMemberDescriptor> D.getDeepestSuperDeclarations(withThis: Boolean = true): Collection<D> {
    val overriddenDeclarations = DescriptorUtils.getAllOverriddenDeclarations(this)
    if (overriddenDeclarations.isEmpty() && withThis) {
        return setOf(this)
    }

    return overriddenDeclarations.filterNot(DescriptorUtils::isOverride)
}

fun <T : DeclarationDescriptor> T.unwrapIfFakeOverride(): T {
    return if (this is CallableMemberDescriptor) DescriptorUtils.unwrapFakeOverride(this) else this
}