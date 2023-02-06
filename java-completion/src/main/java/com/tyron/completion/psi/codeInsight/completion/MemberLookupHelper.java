package com.tyron.completion.psi.codeInsight.completion;

import com.tyron.completion.lookup.LookupElementPresentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiField;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiWildcardType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiFormatUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiFormatUtilBase;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class MemberLookupHelper {

    private final PsiMember myMember;
    private final boolean myMergedOverloads;
    @Nullable
    private final PsiClass myContainingClass;
    private boolean myShouldImport;

    public MemberLookupHelper(List<? extends PsiMethod> overloads, PsiClass containingClass, boolean shouldImport) {
        this(overloads.get(0), containingClass, shouldImport, true);
    }

    public MemberLookupHelper(PsiMember member, @Nullable PsiClass containingClass, boolean shouldImport, final boolean mergedOverloads) {
        myMember = member;
        myContainingClass = containingClass;
        myShouldImport = shouldImport;
        myMergedOverloads = mergedOverloads;
    }

    public PsiMember getMember() {
        return myMember;
    }

    @Nullable
    public PsiClass getContainingClass() {
        return myContainingClass;
    }

    public void setShouldBeImported(boolean shouldImportStatic) {
        myShouldImport = shouldImportStatic;
    }

    public boolean willBeImported() {
        return myShouldImport;
    }

    public void renderElement(LookupElementPresentation presentation, boolean showClass, boolean showPackage, PsiSubstitutor substitutor) {
        final String className = myContainingClass == null ? "???" : myContainingClass.getName();

        final String memberName = myMember.getName();
        boolean constructor = myMember instanceof PsiMethod && ((PsiMethod)myMember).isConstructor();
        if (constructor) {
            presentation.setItemText("new " + memberName);
            if (myContainingClass != null && myContainingClass.getTypeParameters().length > 0) {
                presentation.appendTailText("<>", false);
            }
        }
        else if (showClass && StringUtil.isNotEmpty(className)) {
            presentation.setItemText(className + "." + memberName);
        }
        else {
            presentation.setItemText(memberName);
        }

        final String qname = myContainingClass == null ? "" : myContainingClass.getQualifiedName();
        String pkg = qname == null ? "" : StringUtil.getPackageName(qname);
        String location = showPackage && StringUtil.isNotEmpty(pkg) ? " (" + pkg + ")" : "";

        final String params = myMergedOverloads
                ? "(...)"
                : myMember instanceof PsiMethod
                ? getMethodParameterString((PsiMethod)myMember, substitutor)
                : "";

        presentation.appendTailText(params, false);
        if (myShouldImport && !constructor && StringUtil.isNotEmpty(className)) {
            presentation.appendTailText(" in " + className + location, true);
        } else {
            presentation.appendTailText(location, true);
        }

        PsiType type = getDeclaredType(myMember, substitutor);
        if (type != null) {
            presentation.setTypeText(type.getPresentableText());
        }
    }

    @Nullable
    static PsiType getDeclaredType(PsiMember member, PsiSubstitutor substitutor) {
        if (member instanceof PsiField) {
            return substitutor.substitute(((PsiField) member).getType());
        }
        if (member instanceof PsiMethod) {
            PsiMethod method = (PsiMethod) member;
            if (method.isConstructor()) {
                PsiClass aClass = Objects.requireNonNull(method.getContainingClass());
                return JavaPsiFacade.getElementFactory(method.getProject()).createType(aClass, substitutor);
            }
            return patchGetClass(method, substitutor.substitute(method.getReturnType()));
        }
        return null;
    }

    @Nullable
    private static PsiType patchGetClass(@NotNull PsiMethod method, @Nullable PsiType type) {
        if (PsiTypesUtil.isGetClass(method) && type instanceof PsiClassType) {
            PsiType arg = ContainerUtil.getFirstItem(Arrays.asList(((PsiClassType)type).getParameters()));
            PsiType bound = arg instanceof PsiWildcardType ? TypeConversionUtil.erasure(((PsiWildcardType)arg).getExtendsBound()) : null;
            if (bound != null) {
                return PsiTypesUtil.createJavaLangClassType(method, bound, false);
            }
        }
        return type;
    }


    @NotNull
    static String getMethodParameterString(@NotNull PsiMethod method, @NotNull PsiSubstitutor substitutor) {
        return PsiFormatUtil.formatMethod(method, substitutor,
                PsiFormatUtilBase.SHOW_PARAMETERS, PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_TYPE);
    }
}
