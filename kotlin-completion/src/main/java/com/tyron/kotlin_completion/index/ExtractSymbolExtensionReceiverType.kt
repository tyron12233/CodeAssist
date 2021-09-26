package com.tyron.kotlin_completion.index

import com.tyron.kotlin_completion.util.PsiUtils
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.impl.DeclarationDescriptorVisitorEmptyBodies
import org.jetbrains.kotlin.name.FqName

object ExtractSymbolExtensionReceiverType :
    DeclarationDescriptorVisitorEmptyBodies<FqName?, Unit>() {

    private fun convert(desc: ReceiverParameterDescriptor): FqName? = PsiUtils.getFqNameSafe(desc.value.type.constructor.declarationDescriptor)

    override fun visitFunctionDescriptor(descriptor: FunctionDescriptor, data: Unit?): FqName? {
        return descriptor.extensionReceiverParameter?.let(this::convert)
    }

    override fun visitVariableDescriptor(desc: VariableDescriptor, data: Unit?) = desc.extensionReceiverParameter?.let(this::convert)
}