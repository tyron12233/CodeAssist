package com.tyron.psi.completions.lang.java.filter;


import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;
import org.jetbrains.kotlin.com.intellij.psi.infos.CandidateInfo;

public class ElementExtractorFilter implements ElementFilter {
    private final ElementFilter myFilter;

    public ElementExtractorFilter(ElementFilter filter){
        myFilter = filter;
    }

    public ElementFilter getFilter(){
        return myFilter;
    }

    @Override
    public boolean isClassAcceptable(Class hintClass){
        return myFilter.isClassAcceptable(hintClass);
    }

    @Override
    public boolean isAcceptable(Object element, PsiElement context){
        if(element instanceof CandidateInfo) {
            final CandidateInfo candidateInfo = (CandidateInfo)element;
            final PsiElement psiElement = candidateInfo.getElement();

            return myFilter.isAcceptable(psiElement, context);
        }
        else if(element instanceof PsiElement)
            return myFilter.isAcceptable(element, context);
        return false;
    }


    public String toString(){
        return getFilter().toString();
    }
}