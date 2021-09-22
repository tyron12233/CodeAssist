package com.tyron.kotlin_completion.completion;

import com.tyron.completion.model.CompletionItem;

import org.jetbrains.kotlin.descriptors.ClassDescriptor;
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorVisitor;
import org.jetbrains.kotlin.descriptors.FunctionDescriptor;
import org.jetbrains.kotlin.descriptors.ModuleDescriptor;
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor;
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor;
import org.jetbrains.kotlin.descriptors.PropertyDescriptor;
import org.jetbrains.kotlin.descriptors.PropertyGetterDescriptor;
import org.jetbrains.kotlin.descriptors.PropertySetterDescriptor;
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor;
import org.jetbrains.kotlin.descriptors.ScriptDescriptor;
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor;
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor;
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor;
import org.jetbrains.kotlin.descriptors.VariableDescriptor;
import org.jetbrains.kotlin.descriptors.impl.DeclarationDescriptorImpl;

public class RenderCompletionItem implements DeclarationDescriptorVisitor<CompletionItem, Void> {

    private final boolean snippetsEnabled;
    private final CompletionItem result = new CompletionItem();

    public RenderCompletionItem(boolean snippetsEnabled) {
        this.snippetsEnabled = snippetsEnabled;
    }

    private void setDefaults(DeclarationDescriptor d) {
        result.label = label(d);
        result.commitText = result.label;
    }
    @Override
    public CompletionItem visitPackageFragmentDescriptor(PackageFragmentDescriptor packageFragmentDescriptor, Void unused) {
        setDefaults(packageFragmentDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitPackageViewDescriptor(PackageViewDescriptor packageViewDescriptor, Void unused) {
        setDefaults(packageViewDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitVariableDescriptor(VariableDescriptor variableDescriptor, Void unused) {
        setDefaults(variableDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitFunctionDescriptor(FunctionDescriptor functionDescriptor, Void unused) {
        setDefaults(functionDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitTypeParameterDescriptor(TypeParameterDescriptor typeParameterDescriptor, Void unused) {
        setDefaults(typeParameterDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitClassDescriptor(ClassDescriptor classDescriptor, Void unused) {
        setDefaults(classDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitTypeAliasDescriptor(TypeAliasDescriptor typeAliasDescriptor, Void unused) {
        setDefaults(typeAliasDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitModuleDeclaration(ModuleDescriptor moduleDescriptor, Void unused) {
        setDefaults(moduleDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitConstructorDescriptor(ConstructorDescriptor constructorDescriptor, Void unused) {
        setDefaults(constructorDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitScriptDescriptor(ScriptDescriptor scriptDescriptor, Void unused) {
        setDefaults(scriptDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitPropertyDescriptor(PropertyDescriptor propertyDescriptor, Void unused) {
        setDefaults(propertyDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitValueParameterDescriptor(ValueParameterDescriptor valueParameterDescriptor, Void unused) {
        setDefaults(valueParameterDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitPropertyGetterDescriptor(PropertyGetterDescriptor propertyGetterDescriptor, Void unused) {
        setDefaults(propertyGetterDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitPropertySetterDescriptor(PropertySetterDescriptor propertySetterDescriptor, Void unused) {
        setDefaults(propertySetterDescriptor);
        return result;
    }

    @Override
    public CompletionItem visitReceiverParameterDescriptor(ReceiverParameterDescriptor receiverParameterDescriptor, Void unused) {
        setDefaults(receiverParameterDescriptor);
        return result;
    }

    private String label(DeclarationDescriptor d) {
        if (d instanceof ConstructorDescriptor) {
            return d.getContainingDeclaration().getName().getIdentifier();
        }
        if (d.getName().isSpecial()) {
            return null;
        }

        return d.getName().getIdentifier();
    }

}
