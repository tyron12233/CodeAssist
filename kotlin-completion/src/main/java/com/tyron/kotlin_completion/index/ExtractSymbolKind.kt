package com.tyron.kotlin_completion.index

import org.jetbrains.kotlin.descriptors.*

object ExtractSymbolKind : DeclarationDescriptorVisitor<Symbol.Kind, Unit?> {
    override fun visitPropertyDescriptor(p0: PropertyDescriptor?, p1: Unit?): Symbol.Kind {
        return Symbol.Kind.VARIABLE
    }

    override fun visitConstructorDescriptor(p0: ConstructorDescriptor?, p1: Unit?): Symbol.Kind {
        return Symbol.Kind.CONSTRUCTOR
    }

    override fun visitReceiverParameterDescriptor(
        p0: ReceiverParameterDescriptor?,
        p1: Unit?
    ): Symbol.Kind {
        return Symbol.Kind.MODULE
    }

    override fun visitPackageViewDescriptor(p0: PackageViewDescriptor?, p1: Unit?): Symbol.Kind {
        return Symbol.Kind.MODULE
    }

    override fun visitFunctionDescriptor(p0: FunctionDescriptor?, p1: Unit?): Symbol.Kind {
        return Symbol.Kind.FUNCTION
    }

    override fun visitModuleDeclaration(p0: ModuleDescriptor?, p1: Unit?): Symbol.Kind {
        return Symbol.Kind.MODULE
    }

    override fun visitClassDescriptor(p0: ClassDescriptor, p1: Unit?): Symbol.Kind = when (p0.kind) {
        ClassKind.INTERFACE -> Symbol.Kind.INTERFACE
        ClassKind.ENUM_CLASS -> Symbol.Kind.ENUM
        ClassKind.ENUM_ENTRY -> Symbol.Kind.ENUM_MEMBER
        else -> Symbol.Kind.CLASS
    }

    override fun visitPackageFragmentDescriptor(
        p0: PackageFragmentDescriptor?,
        p1: Unit?
    ): Symbol.Kind {
        return Symbol.Kind.MODULE
    }

    override fun visitValueParameterDescriptor(
        p0: ValueParameterDescriptor?,
        p1: Unit?
    ): Symbol.Kind {
        return Symbol.Kind.VARIABLE
    }

    override fun visitTypeParameterDescriptor(
        p0: TypeParameterDescriptor?,
        p1: Unit?
    ): Symbol.Kind {
        return Symbol.Kind.VARIABLE
    }

    override fun visitScriptDescriptor(p0: ScriptDescriptor?, p1: Unit?): Symbol.Kind {
        return Symbol.Kind.MODULE
    }

    override fun visitTypeAliasDescriptor(p0: TypeAliasDescriptor?, p1: Unit?): Symbol.Kind {
        return Symbol.Kind.MODULE
    }

    override fun visitPropertyGetterDescriptor(
        p0: PropertyGetterDescriptor?,
        p1: Unit?
    ): Symbol.Kind {
        return Symbol.Kind.VARIABLE
    }

    override fun visitVariableDescriptor(p0: VariableDescriptor?, p1: Unit?): Symbol.Kind {
        return Symbol.Kind.VARIABLE
    }

    override fun visitPropertySetterDescriptor(
        p0: PropertySetterDescriptor?,
        p1: Unit?
    ): Symbol.Kind {
        return Symbol.Kind.FIELD
    }
}