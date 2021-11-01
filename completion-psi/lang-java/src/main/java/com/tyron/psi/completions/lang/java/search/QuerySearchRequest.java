package com.tyron.psi.completions.lang.java.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadActionProcessor;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.Query;

/**
 * @author peter
 */
public class QuerySearchRequest {
    public final Query<PsiReference> query;
    public final SearchRequestCollector collector;
    public final Processor<? super PsiReference> processor;

    public QuerySearchRequest(@NotNull Query<PsiReference> query,
                              @NotNull final SearchRequestCollector collector,
                              boolean inReadAction,
                              @NotNull final PairProcessor<? super PsiReference, ? super SearchRequestCollector> processor) {
        this.query = query;
        this.collector = collector;
        if (inReadAction) {
            this.processor = new ReadActionProcessor<PsiReference>() {
                @Override
                public boolean processInReadAction(PsiReference psiReference) {
                    return processor.process(psiReference, collector);
                }
            };
        }
        else {
            this.processor = psiReference -> processor.process(psiReference, collector);
        }
    }

    public boolean runQuery() {
        return query.forEach(processor);
    }

    @Override
    public String toString() {
        return query + " -> " + collector;
    }
}
