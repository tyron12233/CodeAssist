package com.tyron.psi.completions.lang.java.search;


import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.SearchScope;

/**
 * @author peter
 */
public class PsiSearchRequest {
    @NotNull public final SearchScope searchScope;
    @NotNull public final String word;
    public final short searchContext;
    public final boolean caseSensitive;
    public final RequestResultProcessor processor;
    public final String containerName;
    private final SearchSession mySession;

    PsiSearchRequest(@NotNull SearchScope searchScope,
                     @NotNull String word,
                     short searchContext,
                     boolean caseSensitive,
                     @Nullable String containerName,
                     @NotNull SearchSession session,
                     @NotNull RequestResultProcessor processor) {
        this.containerName = containerName;
        mySession = session;
        if (word.isEmpty()) {
            throw new IllegalArgumentException("Cannot search for elements with empty text");
        }
        this.searchScope = searchScope;
        this.word = word;
        this.searchContext = searchContext;
        this.caseSensitive = caseSensitive;
        this.processor = processor;
        if (searchScope instanceof GlobalSearchScope && ((GlobalSearchScope)searchScope).getProject() == null) {
            throw new AssertionError("Every search scope must be associated with a project: " + searchScope);
        }
    }

    @Override
    public String toString() {
        return word + " -> " + processor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PsiSearchRequest)) return false;

        PsiSearchRequest that = (PsiSearchRequest)o;

        if (caseSensitive != that.caseSensitive) return false;
        if (searchContext != that.searchContext) return false;
        if (!processor.equals(that.processor)) return false;
        if (!searchScope.equals(that.searchScope)) return false;
        return word.equals(that.word);
    }

    @Override
    public int hashCode() {
        int result = searchScope.hashCode();
        result = 31 * result + word.hashCode();
        result = 31 * result + (int)searchContext;
        result = 31 * result + (caseSensitive ? 1 : 0);
        result = 31 * result + processor.hashCode();
        return result;
    }

    @NotNull
    public SearchSession getSearchSession() {
        return mySession;
    }
}
