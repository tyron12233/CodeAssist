package com.tyron.completion;

import com.tyron.completion.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;

public class AutoCompletionDecision {
  public static final AutoCompletionDecision SHOW_LOOKUP = new AutoCompletionDecision();
  public static final AutoCompletionDecision CLOSE_LOOKUP = new AutoCompletionDecision();

  public static AutoCompletionDecision insertItem(@NotNull LookupElement element) {
    return new InsertItem(element);
  }

  private AutoCompletionDecision() {
  }

  static final class InsertItem extends AutoCompletionDecision {
    private final LookupElement myElement;

    private InsertItem(LookupElement element) {
      myElement = element;
    }

    public LookupElement getElement() {
      return myElement;
    }
  }

}