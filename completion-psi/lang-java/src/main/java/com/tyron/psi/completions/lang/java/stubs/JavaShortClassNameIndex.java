package com.tyron.psi.completions.lang.java.stubs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

import java.util.Collection;
import java.util.Collections;

public class JavaShortClassNameIndex {
    private static final JavaShortClassNameIndex ourInstance = new JavaShortClassNameIndex();

    public static JavaShortClassNameIndex getInstance() {
        return ourInstance;
    }

    public Collection<PsiClass> get(@NotNull final String shortName, @NotNull final Project project, @NotNull final GlobalSearchScope scope) {
        return Collections.emptyList();
        //return StubIndex.getElements(getKey(), shortName, project, new JavaSourceFilterScope(scope), PsiClass.class);
    }
}
