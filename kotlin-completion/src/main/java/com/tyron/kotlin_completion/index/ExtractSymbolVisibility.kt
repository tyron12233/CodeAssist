package com.tyron.kotlin_completion.index

import org.jetbrains.kotlin.descriptors.*

object ExtractSymbolVisibility : DeclarationDescriptorVisitor<Symbol.Visibility, Unit?> {
    private fun convert(visibility: DescriptorVisibility): Symbol.Visibility = when (visibility.delegate) {
        Visibilities.PrivateToThis -> Symbol.Visibility.PRIVATE_TO_THIS
        Visibilities.Private -> Symbol.Visibility.PRIVATE
        Visibilities.Internal -> Symbol.Visibility.INTERNAL
        Visibilities.Protected -> Symbol.Visibility.PROTECTED
        Visibilities.Public -> Symbol.Visibility.PUBLIC
        else -> Symbol.Visibility.UNKNOWN
    }

    override fun visitPackageFragmentDescriptor(
        p0: PackageFragmentDescriptor?,
        p1: Unit?
    ): Symbol.Visibility {
        return Symbol.Visibility.PUBLIC
    }

    override fun visitPackageViewDescriptor(
        p0: PackageViewDescriptor?,
        p1: Unit?
    ): Symbol.Visibility {
        return Symbol.Visibility.PUBLIC
    }

    override fun visitVariableDescriptor(p0: VariableDescriptor, p1: Unit?): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitFunctionDescriptor(p0: FunctionDescriptor, p1: Unit?): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitTypeParameterDescriptor(
        p0: TypeParameterDescriptor,
        p1: Unit?
    ): Symbol.Visibility {
        return Symbol.Visibility.PUBLIC
    }

    override fun visitClassDescriptor(p0: ClassDescriptor, p1: Unit?): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitTypeAliasDescriptor(p0: TypeAliasDescriptor, p1: Unit?): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitModuleDeclaration(p0: ModuleDescriptor, p1: Unit?): Symbol.Visibility {
        return Symbol.Visibility.PUBLIC
    }

    override fun visitConstructorDescriptor(
        p0: ConstructorDescriptor,
        p1: Unit?
    ): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitScriptDescriptor(p0: ScriptDescriptor, p1: Unit?): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitPropertyDescriptor(p0: PropertyDescriptor, p1: Unit?): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitValueParameterDescriptor(
        p0: ValueParameterDescriptor,
        p1: Unit?
    ): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitPropertyGetterDescriptor(
        p0: PropertyGetterDescriptor,
        p1: Unit?
    ): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitPropertySetterDescriptor(
        p0: PropertySetterDescriptor,
        p1: Unit?
    ): Symbol.Visibility {
        return convert(p0.visibility)
    }

    override fun visitReceiverParameterDescriptor(
        p0: ReceiverParameterDescriptor,
        p1: Unit?
    ): Symbol.Visibility {
        return convert(p0.visibility)
    }

}