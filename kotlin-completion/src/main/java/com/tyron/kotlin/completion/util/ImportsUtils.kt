package com.tyron.kotlin.completion.util

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor

val DeclarationDescriptor.importableFqName: FqName?
    get() {
        if (!canBeReferencedViaImport()) return null
        return getImportableDescriptor().fqNameSafe
    }

fun DeclarationDescriptor.canBeReferencedViaImport(): Boolean {
    if (this is PackageViewDescriptor ||
        DescriptorUtils.isTopLevelDeclaration(this) ||
        this is CallableDescriptor && DescriptorUtils.isStaticDeclaration(this)
    ) {
        return !name.isSpecial
    }

    //Both TypeAliasDescriptor and ClassDescriptor
    val parentClassifier = containingDeclaration as? ClassifierDescriptorWithTypeParameters ?: return false
    if (!parentClassifier.canBeReferencedViaImport()) return false

    return when (this) {
        is ConstructorDescriptor -> !parentClassifier.isInner // inner class constructors can't be referenced via import
        is ClassDescriptor, is TypeAliasDescriptor -> true
        else -> parentClassifier is ClassDescriptor && parentClassifier.kind == ClassKind.OBJECT
    }
}
