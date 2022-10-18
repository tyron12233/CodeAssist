package com.tyron.completion.psi.scope;

import com.tyron.completion.psi.util.CompletionUtilCoreImpl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.Trinity;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceType;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable;
import org.jetbrains.kotlin.com.intellij.psi.util.MethodSignature;
import org.jetbrains.kotlin.com.intellij.psi.util.MethodSignatureUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil;

import java.util.Arrays;

public final class CompletionElement{
    private final Object myElement;
    private final PsiSubstitutor mySubstitutor;
    private final Object myEqualityObject;
    private final String myQualifierText;
    private final @Nullable PsiType myMethodRefType;

    public CompletionElement(Object element, PsiSubstitutor substitutor) {
        this(element, substitutor, "", null);
    }

    CompletionElement(Object element, PsiSubstitutor substitutor, @NotNull String qualifierText, @Nullable PsiType methodRefType) {
        myElement = element;
        mySubstitutor = substitutor;
        myQualifierText = qualifierText;
        myMethodRefType = methodRefType;
        myEqualityObject = getUniqueId();
    }

    @NotNull
    public String getQualifierText() {
        return myQualifierText;
    }

    public PsiSubstitutor getSubstitutor(){
        return mySubstitutor;
    }

    public Object getElement(){
        return myElement;
    }

    @Nullable
    private Object getUniqueId(){
        if(myElement instanceof PsiClass){
            String qName = ((PsiClass)myElement).getQualifiedName();
            return qName == null ? ((PsiClass)myElement).getName() : qName;
        }
        if(myElement instanceof PsiPackage){
            return ((PsiPackage)myElement).getQualifiedName();
        }
        if(myElement instanceof PsiMethod){
            if (myMethodRefType != null) {
                return ((PsiMethod)myElement).isConstructor() ? PsiKeyword.NEW : ((PsiMethod)myElement).getName();
            }

            return Trinity.create(((PsiMethod)myElement).getName(),
                                  Arrays.asList(MethodSignatureUtil.calcErasedParameterTypes(((PsiMethod)myElement).getSignature(mySubstitutor))),
                                  myQualifierText);
        }
        if (myElement instanceof PsiVariable) {
            return CompletionUtilCoreImpl.getOriginalOrSelf((PsiElement)myElement);
        }

        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof CompletionElement)) return false;

        Object thatObj = ((CompletionElement)obj).myEqualityObject;
        if (myEqualityObject instanceof MethodSignature) {
            return thatObj instanceof MethodSignature &&
                   MethodSignatureUtil
                           .areSignaturesErasureEqual((MethodSignature)myEqualityObject, (MethodSignature)thatObj);
        }
        return Comparing.equal(myEqualityObject, thatObj);
    }

    @Override
    public int hashCode() {
        if (myEqualityObject instanceof MethodSignature) {
            return myEqualityObject.hashCode();
        }
        return myEqualityObject != null ? myEqualityObject.hashCode() : 0;
    }

    @Nullable
    public PsiType getMethodRefType() {
        return myMethodRefType;
    }

    public boolean isMoreSpecificThan(@NotNull CompletionElement another) {
        Object anotherElement = another.getElement();
        if (!(anotherElement instanceof PsiMethod && myElement instanceof PsiMethod)) return false;

        if (another.myMethodRefType instanceof PsiMethodReferenceType && myMethodRefType instanceof PsiClassType) {
            return true;
        }

        if (anotherElement != myElement &&
            ((PsiMethod)myElement).hasModifierProperty(PsiModifier.ABSTRACT) &&
            !((PsiMethod)anotherElement).hasModifierProperty(PsiModifier.ABSTRACT)) {
            return false;
        }

        PsiType prevType = TypeConversionUtil.erasure(another.getSubstitutor().substitute(((PsiMethod)anotherElement).getReturnType()));
        PsiType candidateType = mySubstitutor.substitute(((PsiMethod)myElement).getReturnType());
        return prevType != null && candidateType != null && !prevType.equals(candidateType) && prevType.isAssignableFrom(candidateType);
    }

    @Override
    public String toString() {
        return "CompletionElement{" +
               "myElement=" +
               myElement +
               ", mySubstitutor=" +
               mySubstitutor +
               ", myEqualityObject=" +
               myEqualityObject +
               ", myQualifierText='" +
               myQualifierText +
               '\'' +
               ", myMethodRefType=" +
               myMethodRefType +
               '}';
    }
}