package com.tyron.completion.impl;

import com.tyron.completion.CompletionUtil;
import com.tyron.completion.lookup.Classifier;
import com.tyron.completion.lookup.Lookup;
import com.tyron.completion.lookup.LookupElement;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.containers.CollectionFactory;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.FilteringIterator;
import org.jetbrains.kotlin.com.intellij.util.containers.FlatteningIterator;
import org.jetbrains.kotlin.com.intellij.util.containers.MultiMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.lang.reflect.Field;
import java.util.*;

public final class LiftShorterItemsClassifier extends Classifier<LookupElement> {
    private final TreeSet<String> mySortedStrings = new TreeSet<>();
    private final MultiMap<String, LookupElement> myElements = createMultiMap(false);
    private final MultiMap<LookupElement, LookupElement> myToLift = createMultiMap(true);
    private final MultiMap<LookupElement, LookupElement> myReversedToLift = createMultiMap(true);
    private final LiftingCondition myCondition;
    private final boolean myLiftBefore;
    private volatile int myCount = 0;

    public LiftShorterItemsClassifier(@NonNls String name,
                                      Classifier<LookupElement> next,
                                      LiftingCondition condition,
                                      boolean liftBefore) {
        super(next, name);
        myCondition = condition;
        myLiftBefore = liftBefore;
    }

    @Override
    public void addElement(@NotNull LookupElement added, @NotNull ProcessingContext context) {
        synchronized (this) {
            myCount++;

            for (String string : CompletionUtil.iterateLookupStrings(added)) {
                if (string.length() == 0) {
                    continue;
                }

                myElements.putValue(string, added);
                mySortedStrings.add(string);
                final NavigableSet<String> after = mySortedStrings.tailSet(string, false);
                for (String s : after) {
                    if (!s.startsWith(string)) {
                        break;
                    }
                    for (LookupElement longer : myElements.get(s)) {
                        updateLongerItem(added, longer);
                    }
                }
            }
        }
        super.addElement(added, context);

        calculateToLift(added);
    }

    private void updateLongerItem(LookupElement shorter, LookupElement longer) {
        if (myCondition.shouldLift(shorter, longer)) {
            myToLift.putValue(longer, shorter);
            myReversedToLift.putValue(shorter, longer);
        }
    }

    private synchronized void calculateToLift(@NotNull LookupElement element) {
        for (String string : CompletionUtil.iterateLookupStrings(element)) {
            for (int len = 1; len < string.length(); len++) {
                String prefix = string.substring(0, len);
                for (LookupElement shorterElement : myElements.get(prefix)) {
                    if (myCondition.shouldLift(shorterElement, element)) {
                        myToLift.putValue(element, shorterElement);
                        myReversedToLift.putValue(shorterElement, element);
                    }
                }
            }
        }
    }

    @NotNull
    @Override
    public Iterable<LookupElement> classify(@NotNull Iterable<? extends LookupElement> source,
                                            @NotNull ProcessingContext context) {
        return liftShorterElements(source, null, context);
    }

    private @NotNull Iterable<LookupElement> liftShorterElements(@NotNull Iterable<?
            extends LookupElement> source,
                                                                 @Nullable Set<?
                                                                         super LookupElement> lifted,
                                                                 @NotNull ProcessingContext context) {
        Set<LookupElement> srcSet =
                new ReferenceOpenHashSet<>(source instanceof Collection ?
                        ((Collection<?>) source).size() : myCount);
        for (LookupElement lookupElement : source) {
            srcSet.add(lookupElement);
        }
        if (srcSet.size() < 2) {
            return myNext.classify(source, context);
        }
        return new LiftingIterable(srcSet, context, source, lifted);
    }

    @NotNull
    @Override
    public List<Pair<LookupElement, Object>> getSortingWeights(@NotNull Iterable<?
            extends LookupElement> items,
                                                               @NotNull ProcessingContext context) {
        Set<LookupElement> lifted = new ReferenceOpenHashSet<>();
        Iterable<LookupElement> iterable =
                liftShorterElements(ContainerUtil.newArrayList(items), lifted, context);
        return ContainerUtil.map(iterable,
                element -> new Pair<>(element, lifted.contains(element)));
    }

    @Override
    public void removeElement(@NotNull LookupElement element, @NotNull ProcessingContext context) {
        synchronized (this) {
            for (String s : CompletionUtil.iterateLookupStrings(element)) {
                myElements.remove(s, element);
                if (myElements.get(s).isEmpty()) {
                    mySortedStrings.remove(s);
                }
            }

            removeFromMap(element, myToLift, myReversedToLift);
            removeFromMap(element, myReversedToLift, myToLift);
        }

        super.removeElement(element, context);
    }

    private static void removeFromMap(LookupElement key,
                                      @NotNull MultiMap<LookupElement, LookupElement> mainMap,
                                      @NotNull MultiMap<LookupElement, LookupElement> inverseMap) {
        try {
            Field myMap = MultiMap.class.getDeclaredField("myMap");
            myMap.setAccessible(true);
            @SuppressWarnings("unchecked") Map<LookupElement, Collection<LookupElement>> map =
                    (Map<LookupElement, Collection<LookupElement>>) myMap.get(key);
            if (map == null) {
                return;
            }

            Collection<LookupElement> removed = map.remove(key);
            if (removed == null) {
                return;
            }

            for (LookupElement reference : new ArrayList<>(removed)) {
                inverseMap.remove(reference, key);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static class LiftingCondition {
        public boolean shouldLift(LookupElement shorterElement, LookupElement longerElement) {
            return true;
        }
    }

    private class LiftingIterable implements Iterable<LookupElement> {
        private final @NotNull Set<LookupElement> mySrcSet;
        private final @NotNull ProcessingContext myContext;
        private final @NotNull Iterable<? extends LookupElement> mySource;
        private final @Nullable Set<? super LookupElement> myLifted;

        LiftingIterable(@NotNull Set<LookupElement> srcSet,
                        @NotNull ProcessingContext context,
                        @NotNull Iterable<? extends LookupElement> source,
                        @Nullable Set<? super LookupElement> lifted) {
            mySrcSet = srcSet;
            myContext = context;
            mySource = source;
            myLifted = lifted;
        }

        @Override
        public @NotNull Iterator<LookupElement> iterator() {
            Set<LookupElement> processed = new ReferenceOpenHashSet<>(mySrcSet.size());
            Set<Collection<LookupElement>> arraysProcessed = new ReferenceOpenHashSet<>();

            final Iterable<? extends LookupElement> next =
                    myNext == null ? mySource : myNext.classify(mySource, myContext);
            Iterator<LookupElement> base = new FilteringIterator<>(next.iterator(), processed::add);
            return new FlatteningIterator<LookupElement, LookupElement>(base) {
                @Override
                protected @NotNull Iterator<LookupElement> createValueIterator(LookupElement element) {
                    List<LookupElement> toLift;
                    synchronized (LiftShorterItemsClassifier.this) {
                        toLift = new ArrayList<>(myToLift.get(element));
                    }
                    List<LookupElement> shorter = addShorterElements(toLift);
                    List<LookupElement> singleton = Collections.singletonList(element);
                    if (shorter != null) {
                        if (myLifted != null) {
                            myLifted.addAll(shorter);
                        }
                        Iterable<LookupElement> lifted =
                                myNext == null ? shorter : myNext.classify(shorter, myContext);
                        return (myLiftBefore ? ContainerUtil.concat(lifted,
                                singleton) : ContainerUtil.concat(singleton, lifted)).iterator();
                    }
                    return singleton.iterator();
                }

                @Nullable
                private List<LookupElement> addShorterElements(@Nullable Collection<LookupElement> from) {
                    List<LookupElement> toLift = null;
                    if (from == null) {
                        return null;
                    }

                    if (arraysProcessed.add(from)) {
                        for (LookupElement shorterElement : from) {
                            if (mySrcSet.contains(shorterElement) &&
                                processed.add(shorterElement)) {
                                if (toLift == null) {
                                    toLift = new ArrayList<>();
                                }
                                toLift.add(shorterElement);
                            }
                        }
                    }
                    return toLift;
                }
            };
        }
    }

    @SuppressWarnings("NonExtendableApiUsage")
    private static @NotNull <K, V> MultiMap<K, V> createMultiMap(boolean identityKeys) {
        return new MultiMap<K, V>(identityKeys ? new Reference2ObjectOpenHashMap<>() : CollectionFactory.createSmallMemoryFootprintMap()) {
            @Override
            public boolean remove(K key, V value) {
                List<V> elements = (List<V>) get(key);
                int i = ContainerUtil.indexOfIdentity(elements, value);
                if (i >= 0) {
                    elements.remove(i);
                    if (elements.isEmpty()) {
                        this.myMap.remove(key);
                    }
                    return true;
                }
                return false;
            }
        };
    }
}