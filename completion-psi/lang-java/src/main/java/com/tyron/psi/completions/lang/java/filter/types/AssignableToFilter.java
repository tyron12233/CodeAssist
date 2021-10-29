package com.tyron.psi.completions.lang.java.filter.types;

import com.tyron.psi.completions.lang.java.filter.FilterUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;
import org.jetbrains.kotlin.com.intellij.psi.infos.CandidateInfo;

public class AssignableToFilter implements ElementFilter {
    private final PsiType myType;

    public AssignableToFilter(@NotNull PsiType type){
        myType = type;
    }

    @Override
    public boolean isClassAcceptable(Class hintClass){
        return true;
    }

    @Override
    public boolean isAcceptable(Object element, PsiElement context){
        if(element == null) return false;
        if (element instanceof PsiType) return myType.isAssignableFrom((PsiType) element);
        PsiSubstitutor substitutor = null;
        if(element instanceof CandidateInfo){
            final CandidateInfo info = (CandidateInfo)element;
            substitutor = info.getSubstitutor();
            element = info.getElement();
        }

        PsiType typeByElement = FilterUtil.getTypeByElement((PsiElement)element, context);
        if(substitutor != null) typeByElement = substitutor.substitute(typeByElement);
        return typeByElement != null && typeByElement.isAssignableFrom(myType) && !typeByElement.equals(myType);
    }

    public String toString(){
        return "assignable-to(" + myType + ")";
    }
}