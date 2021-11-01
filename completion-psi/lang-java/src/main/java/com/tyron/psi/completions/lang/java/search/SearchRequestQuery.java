package com.tyron.psi.completions.lang.java.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.util.AbstractQuery;
import org.jetbrains.kotlin.com.intellij.util.Processor;

/**
 * @author peter
 */
public class SearchRequestQuery extends AbstractQuery<PsiReference> {
    private final Project myProject;
    private final SearchRequestCollector myRequests;

    public SearchRequestQuery(@NotNull Project project, @NotNull SearchRequestCollector requests) {
        myProject = project;
        myRequests = requests;
    }

    @Override
    protected boolean processResults(@NotNull Processor<? super PsiReference> consumer) {
        return PsiSearchHelper.getInstance(myProject).processRequests(myRequests, consumer);
    }

    @Override
    public String toString() {
        return myRequests.toString();
    }
}
