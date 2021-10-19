package com.tyron.lookup;

/**
 * @author peter
 */
public abstract class LookupElementRenderer<T extends LookupElement> {
    public abstract void renderElement(final T element, LookupElementPresentation presentation);
}
