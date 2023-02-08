package com.tyron.completion.lookup;

import com.tyron.completion.impl.CompletionLookupArranger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class CachingComparingClassifier extends ComparingClassifier<LookupElement> {
  private final Map<LookupElement, Comparable> myWeights = new IdentityHashMap<>();
  private final LookupElementWeigher myWeigher;
  private Ref<Comparable> myFirstWeight;
  private volatile boolean myPrimitive = true;
  private int myPrefixChanges = -1;

  public CachingComparingClassifier(Classifier<LookupElement> next, LookupElementWeigher weigher) {
    super(next, weigher.toString(), weigher.isNegated());
    myWeigher = weigher;
  }

  @Nullable
  @Override
  public final Comparable getWeight(LookupElement element, ProcessingContext context) {
    Comparable w = myWeights.get(element);
    if (w == null && myWeigher.isPrefixDependent()) {
      myWeights.put(element, w = myWeigher.weigh(element,
              (WeighingContext) context.get(CompletionLookupArranger.WEIGHING_CONTEXT)));
    }
    return w;
  }

  @Override
  public void removeElement(@NotNull LookupElement element, @NotNull ProcessingContext context) {
    synchronized (this) {
      myWeights.remove(element);
    }
    super.removeElement(element, context);
  }

  @NotNull
  @Override
  public Iterable<LookupElement> classify(@NotNull Iterable<? extends LookupElement> source, @NotNull ProcessingContext context) {
    if (!myWeigher.isPrefixDependent() && myPrimitive) {
      return myNext.classify(source, context);
    }
    checkPrefixChanged(context);

    return super.classify(source, context);
  }

  private synchronized void checkPrefixChanged(ProcessingContext context) {
    int actualPrefixChanges = (int) context.get(CompletionLookupArranger.PREFIX_CHANGES);
    if (myWeigher.isPrefixDependent() && myPrefixChanges != actualPrefixChanges) {
      myPrefixChanges = actualPrefixChanges;
      myWeights.clear();
    }
  }

  @NotNull
  @Override
  public List<Pair<LookupElement, Object>> getSortingWeights(@NotNull Iterable<? extends LookupElement> items, @NotNull ProcessingContext context) {
    checkPrefixChanged(context);
    return super.getSortingWeights(items, context);
  }

  @Override
  public void addElement(@NotNull LookupElement t, @NotNull ProcessingContext context) {
    Comparable<?> weight = myWeigher.weigh(t,
            (WeighingContext) context.get(CompletionLookupArranger.WEIGHING_CONTEXT));
//    if (weight instanceof ForceableComparable) {
//      ((ForceableComparable)weight).force();
//    }
    synchronized (this) {
      if (!myWeigher.isPrefixDependent() && myPrimitive) {
        if (myFirstWeight == null) {
          myFirstWeight = Ref.create(weight);
        } else if (!Comparing.equal(myFirstWeight.get(), weight)) {
          myPrimitive = false;
        }
      }
      myWeights.put(t, weight);
    }
    super.addElement(t, context);
  }

}