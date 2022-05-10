package com.tyron.builder.model.internal.inspect;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.model.RuleSource;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class ModelRuleSourceDetector {

    private static final Comparator<Class<?>> COMPARE_BY_CLASS_NAME = new Comparator<Class<?>>() {
        @Override
        public int compare(Class<?> left, Class<?> right) {
            return left.getName().compareTo(right.getName());
        }
    };

    final LoadingCache<Class<?>, Collection<Reference<Class<? extends RuleSource>>>> cache = CacheBuilder.newBuilder()
            .weakKeys()
            .build(new CacheLoader<Class<?>, Collection<Reference<Class<? extends RuleSource>>>>() {
                @Override
                public Collection<Reference<Class<? extends RuleSource>>> load(@SuppressWarnings("NullableProblems") Class<?> container) throws Exception {
                    if (isRuleSource(container)) {
                        Class<? extends RuleSource> castClass = Cast.uncheckedCast(container);
                        return ImmutableSet.<Reference<Class<? extends RuleSource>>>of(new WeakReference<Class<? extends RuleSource>>(castClass));
                    }

                    Class<?>[] declaredClasses = container.getDeclaredClasses();

                    if (declaredClasses.length == 0) {
                        return Collections.emptySet();
                    } else {
                        Class<?>[] sortedDeclaredClasses = new Class<?>[declaredClasses.length];
                        System.arraycopy(declaredClasses, 0, sortedDeclaredClasses, 0, declaredClasses.length);
                        Arrays.sort(sortedDeclaredClasses, COMPARE_BY_CLASS_NAME);

                        ImmutableList.Builder<Reference<Class<? extends RuleSource>>> found = ImmutableList.builder();
                        for (Class<?> declaredClass : sortedDeclaredClasses) {
                            if (isRuleSource(declaredClass)) {
                                Class<? extends RuleSource> castClass = Cast.uncheckedCast(declaredClass);
                                found.add(new WeakReference<Class<? extends RuleSource>>(castClass));
                            }
                        }

                        return found.build();
                    }
                }
            });

    // TODO return a richer data structure that provides meta data about how the source was found, for use is diagnostics
    public Iterable<Class<? extends RuleSource>> getDeclaredSources(Class<?> container) {
        try {
            return cache.get(container).stream()
                    .map((Function<Reference<Class<? extends RuleSource>>, Class<?
                            extends RuleSource>>) Reference::get).filter(Objects::nonNull).collect(Collectors.toList());
        } catch (ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    public boolean hasRules(Class<?> container) {
        return !Iterables.isEmpty(getDeclaredSources(container));
    }

    private boolean isRuleSource(Class<?> clazz) {
        return RuleSource.class.isAssignableFrom(clazz);
    }
}
