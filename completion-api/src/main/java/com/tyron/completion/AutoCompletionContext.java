package com.tyron.completion;

import com.tyron.completion.lookup.Lookup;
import com.tyron.completion.lookup.LookupElement;

public class AutoCompletionContext {
  private final CompletionParameters myParameters;
  private final LookupElement[] myItems;
  private final OffsetMap myOffsetMap;
  private final Lookup myLookup;

  public AutoCompletionContext(CompletionParameters parameters, LookupElement[] items, OffsetMap offsetMap, Lookup lookup) {
    myParameters = parameters;
    myItems = items;
    myOffsetMap = offsetMap;
    myLookup = lookup;
  }

  public Lookup getLookup() {
    return myLookup;
  }

  public CompletionParameters getParameters() {
    return myParameters;
  }

  public LookupElement[] getItems() {
    return myItems;
  }

  public OffsetMap getOffsetMap() {
    return myOffsetMap;
  }

}