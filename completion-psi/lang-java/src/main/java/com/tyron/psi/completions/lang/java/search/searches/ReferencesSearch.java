package com.tyron.psi.completions.lang.java.search.searches;

import com.tyron.psi.completions.lang.java.search.PsiSearchHelper;
import com.tyron.psi.completions.lang.java.search.QuerySearchRequest;
import com.tyron.psi.completions.lang.java.search.SearchParameters;
import com.tyron.psi.completions.lang.java.search.SearchRequestCollector;
import com.tyron.psi.completions.lang.java.search.SearchRequestQuery;
import com.tyron.psi.completions.lang.java.search.SearchSession;
import com.tyron.psi.completions.lang.java.util.MergeQuery;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.LocalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.SearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.searches.ExtensibleQueryFactory;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.Function;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.Query;
import org.jetbrains.kotlin.com.intellij.util.QueryExecutor;
import org.jetbrains.kotlin.com.intellij.util.UniqueResultsQuery;

import gnu.trove.TObjectHashingStrategy;

/**
 * Locates all references to a specified PSI element.
 *
 * @author max
 * @see PsiReference
 * @see ReferenceSearcher
 */
public final class ReferencesSearch extends ExtensibleQueryFactory<PsiReference, ReferencesSearch.SearchParameters> {
    public static final ExtensionPointName<QueryExecutor<PsiReference, SearchParameters>> EP_NAME = ExtensionPointName.create("com.intellij.referencesSearch");
    private static final ReferencesSearch INSTANCE = new ReferencesSearch();

    private ReferencesSearch() {
        super(EP_NAME);
    }

    public static class SearchParameters implements DumbAwareSearchParameters, com.tyron.psi.completions.lang.java.search.SearchParameters<PsiReference> {
        private final PsiElement myElementToSearch;
        private final SearchScope myScope;
        private volatile SearchScope myEffectiveScope;
        private final boolean myIgnoreAccessScope;
        private final SearchRequestCollector myOptimizer;
        private final Project myProject;
        private final boolean isSharedOptimizer;

        public SearchParameters(@NotNull PsiElement elementToSearch, @NotNull SearchScope scope, boolean ignoreAccessScope, @Nullable SearchRequestCollector optimizer) {
            myElementToSearch = elementToSearch;
            myScope = scope;
            myIgnoreAccessScope = ignoreAccessScope;
            isSharedOptimizer = optimizer != null;
            myOptimizer = optimizer == null ? new SearchRequestCollector(new SearchSession(elementToSearch)) : optimizer;
            myProject = PsiUtilCore.getProjectInReadAction(elementToSearch);
        }

        public SearchParameters(@NotNull PsiElement elementToSearch, @NotNull SearchScope scope, boolean ignoreAccessScope) {
            this(elementToSearch, scope, ignoreAccessScope, null);
        }

        @Override
        public final boolean areValid() {
            return isQueryValid();
        }

        @Override
        public boolean isQueryValid() {
            return myElementToSearch.isValid();
        }

        @Override
        @NotNull
        public Project getProject() {
            return myProject;
        }

        @NotNull
        public PsiElement getElementToSearch() {
            return myElementToSearch;
        }

        /**
         * @return the user-visible search scope, most often "Project Files" or "Project and Libraries".
         * Searchers most likely need to use {@link #getEffectiveSearchScope()}.
         */
        public SearchScope getScopeDeterminedByUser() {
            return myScope;
        }


        /**
         * @deprecated Same as {@link #getScopeDeterminedByUser()}, use {@link #getEffectiveSearchScope} instead
         */
        @Deprecated
        @NotNull
        public SearchScope getScope() {
            return myScope;
        }

        public boolean isIgnoreAccessScope() {
            return myIgnoreAccessScope;
        }

        @NotNull
        public SearchRequestCollector getOptimizer() {
            return myOptimizer;
        }

        @NotNull
        public SearchScope getEffectiveSearchScope () {
            if (myIgnoreAccessScope) {
                return myScope;
            }

            SearchScope scope = myEffectiveScope;
            if (scope == null) {
                if (!myElementToSearch.isValid()) return LocalSearchScope.EMPTY;

                SearchScope useScope = PsiSearchHelper.getInstance(myElementToSearch.getProject()).getUseScope(myElementToSearch);
                myEffectiveScope = scope = myScope.intersectWith(useScope);
            }
            return scope;
        }
    }

    /**
     * Searches for references to the specified element in the scope in which such references are expected to be found, according to
     * dependencies and access rules.
     *
     * @param element the element (declaration) the references to which are requested.
     * @return the query allowing to enumerate the references.
     */
    @NotNull
    public static Query<PsiReference> search(@NotNull PsiElement element) {
        return search(element, GlobalSearchScope.allScope(PsiUtilCore.getProjectInReadAction(element)), false);
    }

    /**
     * Searches for references to the specified element in the specified scope.
     *
     * @param element the element (declaration) the references to which are requested.
     * @param searchScope the scope in which the search is performed.
     * @return the query allowing to enumerate the references.
     */
    @NotNull
    public static Query<PsiReference> search(@NotNull PsiElement element, @NotNull SearchScope searchScope) {
        return search(element, searchScope, false);
    }

    /**
     * Searches for references to the specified element in the specified scope, optionally returning also references which
     * are invalid because of access rules (e.g. references to a private method from a different class).
     *
     * @param element the element (declaration) the references to which are requested.
     * @param searchScope the scope in which the search is performed.
     * @param ignoreAccessScope if true, references which are invalid because of access rules are included in the results.
     * @return the query allowing to enumerate the references.
     */
    @NotNull
    public static Query<PsiReference> search(@NotNull PsiElement element, @NotNull SearchScope searchScope, boolean ignoreAccessScope) {
        return search(new SearchParameters(element, searchScope, ignoreAccessScope));
    }

    /**
     * Searches for references to the specified element according to the specified parameters.
     *
     * @param parameters the parameters for the search (contain also the element the references to which are requested).
     * @return the query allowing to enumerate the references.
     */
    @NotNull
    public static Query<PsiReference> search(@NotNull SearchParameters parameters) {
        Query<PsiReference> result = INSTANCE.createQuery(parameters);
        if (parameters.isSharedOptimizer) {
            return uniqueResults(result);
        }

        SearchRequestCollector requests = parameters.getOptimizer();

        return uniqueResults(new MergeQuery<>(result, new SearchRequestQuery(parameters.getProject(), requests)));
    }

    @NotNull
    private static Query<PsiReference> uniqueResults(@NotNull Query<? extends PsiReference> composite) {
        return new UniqueResultsQuery<>(composite, TObjectHashingStrategy.CANONICAL);
    }

    public static void searchOptimized(@NotNull PsiElement element,
                                       @NotNull SearchScope searchScope,
                                       boolean ignoreAccessScope,
                                       @NotNull SearchRequestCollector collector,
                                       @NotNull Processor<? super PsiReference> processor) {
        searchOptimized(element, searchScope, ignoreAccessScope, collector, false,
                (psiReference, collector1) -> processor.process(psiReference));
    }

    public static void searchOptimized(@NotNull PsiElement element,
                                       @NotNull SearchScope searchScope,
                                       boolean ignoreAccessScope,
                                       @NotNull SearchRequestCollector collector,
                                       boolean inReadAction,
                                       @NotNull PairProcessor<? super PsiReference, ? super SearchRequestCollector> processor) {
        SearchRequestCollector nested = new SearchRequestCollector(collector.getSearchSession());
        Query<PsiReference> query = search(new SearchParameters(element, searchScope, ignoreAccessScope, nested));
        collector.searchQuery(new QuerySearchRequest(query, nested, inReadAction, processor));
    }
}
