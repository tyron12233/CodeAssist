package org.jetbrains.kotlin.com.intellij.util;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;

import javaslang.collection.List;
import kotlin.jvm.functions.Function1;

public class QueriesImpl extends Queries {
    @Override
    protected @NotNull <I, O> Query<O> transforming(@NotNull Query<? extends I> base,
                                                    @NotNull Function<? super I, ?
                                                            extends Collection<? extends O>> transformation) {
        return new XQuery<I, O>(base, TransformationKt.xValueTransform(transformation::apply));
    }

    @Override
    protected @NotNull <I, O> Query<O> flatMapping(@NotNull Query<? extends I> base,
                                                   @NotNull Function<? super I, ? extends Query<?
                                                           extends O>> mapper) {

        return new XQuery<I, O>(base, TransformationKt.xQueryTransform((Function1<I, Collection<?
                extends Query<? extends O>>>) i -> Collections.singleton(mapper.apply(i))));
    }
}
