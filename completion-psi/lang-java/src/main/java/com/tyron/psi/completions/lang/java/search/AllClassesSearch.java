package com.tyron.psi.completions.lang.java.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.util.Conditions;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.search.SearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.searches.ExtensibleQueryFactory;
import org.jetbrains.kotlin.com.intellij.util.Query;
import org.jetbrains.kotlin.com.intellij.util.QueryExecutor;

public class AllClassesSearch extends ExtensibleQueryFactory<PsiClass, AllClassesSearch.SearchParameters> {

    public static final ExtensionPointName<QueryExecutor<PsiClass, SearchParameters>> EP_NAME = ExtensionPointName.create("com.intellij.allClassesSearch");
    public static final AllClassesSearch INSTANCE = new AllClassesSearch();

    private AllClassesSearch() {
        super(EP_NAME);
    }

    public static class SearchParameters {
        private final SearchScope myScope;
        private final Project myProject;
        private final Condition<? super String> myShortNameCondition;

        public SearchParameters(@NotNull SearchScope scope, @NotNull Project project) {
            this(scope, project, Conditions.alwaysTrue());
        }

        public SearchParameters(@NotNull SearchScope scope, @NotNull Project project, @NotNull Condition<? super String> shortNameCondition) {
            myScope = scope;
            myProject = project;
            myShortNameCondition = shortNameCondition;
        }

        @NotNull
        public SearchScope getScope() {
            return myScope;
        }

        @NotNull
        public Project getProject() {
            return myProject;
        }

        public boolean nameMatches(@NotNull String name) {
            return myShortNameCondition.value(name);
        }
    }


    @NotNull
    public static Query<PsiClass> search(@NotNull SearchScope scope, @NotNull Project project) {
        return INSTANCE.createQuery(new SearchParameters(scope, project));
    }

    @NotNull
    public static Query<PsiClass> search(@NotNull SearchScope scope, @NotNull Project project, @NotNull Condition<? super String> shortNameCondition) {
        return INSTANCE.createQuery(new SearchParameters(scope, project, shortNameCondition));
    }
}
