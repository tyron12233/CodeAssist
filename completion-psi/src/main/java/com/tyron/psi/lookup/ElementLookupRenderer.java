package com.tyron.psi.lookup;

import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;

/**
 * @deprecated use {@link LookupElement#renderElement(LookupElementPresentation)}
 */
@Deprecated
public interface ElementLookupRenderer<T> {
    ExtensionPointName<ElementLookupRenderer> EP_NAME = ExtensionPointName.create("com.intellij.elementLookupRenderer");

    boolean handlesItem(Object element);
    void renderElement(final LookupItem item, T element, LookupElementPresentation presentation);

}
