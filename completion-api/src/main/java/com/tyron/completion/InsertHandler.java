package com.tyron.completion;

import androidx.annotation.NonNull;

import com.tyron.completion.lookup.LookupElement;

/**
 * An object allowing to decouple {@link LookupElement#handleInsert} logic from the lookup element class, e.g. for the purposes
 * of overriding its behavior or reusing the logic between multiple types of elements.
 * @see com.intellij.codeInsight.lookup.LookupElementDecorator#withInsertHandler
 * @see com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
 */
public interface InsertHandler<T extends LookupElement> {

    /**
     * Invoked inside write action.
     */
    void handleInsert(@NonNull InsertionContext context, @NonNull T item);
}