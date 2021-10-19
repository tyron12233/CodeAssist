package com.tyron.completions;

import com.tyron.lookup.LookupElement;
import com.tyron.lookup.LookupElementDecorator;

import org.jetbrains.annotations.NotNull;

/**
 * An object allowing to decouple {@link LookupElement#handleInsert} logic from the lookup element class, e.g. for the purposes
 * of overriding its behavior or reusing the logic between multiple types of elements.
 * @see LookupElementDecorator#withInsertHandler
 * @see ParenthesesInsertHandler
 */
public interface InsertHandler<T extends LookupElement> {

    /**
     * Invoked inside write action.
     */
    void handleInsert(@NotNull InsertionContext context, @NotNull T item);
}
