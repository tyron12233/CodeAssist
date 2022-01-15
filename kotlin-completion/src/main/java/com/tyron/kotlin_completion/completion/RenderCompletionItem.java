package com.tyron.kotlin_completion.completion;

import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.model.CompletionItem;

import org.jetbrains.kotlin.builtins.FunctionTypesKt;
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
import org.jetbrains.kotlin.renderer.ClassifierNamePolicy;
import org.jetbrains.kotlin.renderer.DescriptorRenderer;
import org.jetbrains.kotlin.renderer.ParameterNameRenderingPolicy;
import org.jetbrains.kotlin.types.ErrorUtils;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.types.UnresolvedType;

import java.util.Collections;
import java.util.List;

import kotlin.Unit;
import kotlin.sequences.Sequence;
import kotlin.sequences.SequencesKt;
import kotlin.text.Regex;

public class RenderCompletionItem implements DeclarationDescriptorVisitor<CompletionItem, Void> {

    private final boolean snippetsEnabled;
    private final CompletionItem result = new CompletionItem();

    DescriptorRenderer DECL_RENDERER = DescriptorRenderer.Companion.withOptions(it -> {
        it.setWithDefinedIn(false);
        it.setModifiers(Collections.emptySet());
        it.setClassifierNamePolicy(ClassifierNamePolicy.SHORT.INSTANCE);
        it.setParameterNameRenderingPolicy(ParameterNameRenderingPolicy.ONLY_NON_SYNTHESIZED);
        it.setTypeNormalizer(kotlinType -> {
            if (kotlinType instanceof UnresolvedType) {
                return ErrorUtils.createErrorTypeWithCustomDebugName(((UnresolvedType) kotlinType).getPresentableName());
            }
            return kotlinType;
        });
        return Unit.INSTANCE;
    });

    public RenderCompletionItem(boolean snippetsEnabled) {
        this.snippetsEnabled = snippetsEnabled;
    }

    private void setDefaults(DeclarationDescriptor d) {
        result.label = label(d);
        result.commitText = escape(label(d));
        result.detail = DECL_RENDERER.render(d);
    }

    private String functionInsertText(FunctionDescriptor d) {
        String name = escape(label(d));

        return CompletionUtilsKt.functionInsertText(d, snippetsEnabled, name);
    }

    public static boolean isFunctionType(KotlinType d) {
        if (d == null) {
            return false;
        }
        return FunctionTypesKt.isFunctionType(d);
    }

    private String valueParametersSnippet(List<ValueParameterDescriptor> parameters) {
        Sequence<ValueParameterDescriptor> sequence = SequencesKt.asSequence(parameters.iterator());
        Sequence<ValueParameterDescriptor> filter = SequencesKt.filterNot(sequence, ValueParameterDescriptor::declaresDefaultValue);
        Sequence<String> map = SequencesKt.mapIndexed(filter, (index, vpd) -> "$" + (index + 1) + ":" + vpd.getName());
        return SequencesKt.joinToString(map, "", "", "", 0, "", null);
    }

    private final Regex GOOD_IDENTIFIER = new Regex("[a-zA-Z]\\w*");

    private String escape(String id) {
        if (GOOD_IDENTIFIER.matches(id)) {
            return id;
        }
        return '`' + id + '`';
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

        result.iconKind = DrawableKind.LocalVariable;

        return result;
    }

    @Override
    public CompletionItem visitFunctionDescriptor(FunctionDescriptor functionDescriptor, Void unused) {
        setDefaults(functionDescriptor);

        result.iconKind = DrawableKind.Method;
        result.commitText = functionInsertText(functionDescriptor);

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

        switch (classDescriptor.getKind()) {
            case INTERFACE: result.iconKind = DrawableKind.Interface; break;
            default:
            case CLASS: result.iconKind = DrawableKind.Class; break;
        }


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

        result.iconKind = DrawableKind.Field;

        return result;
    }

    @Override
    public CompletionItem visitPropertySetterDescriptor(PropertySetterDescriptor propertySetterDescriptor, Void unused) {
        setDefaults(propertySetterDescriptor);

        result.iconKind = DrawableKind.Field;
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
