package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;

/**
 * Represents an Android array resource with a name and a list of children {@link ResourceValue}
 * items, one for array element.
 */
public interface ArrayResourceValue extends ResourceValue, Iterable<String> {
    /**
     * Returns the number of elements in this array.
     *
     * @return the element count
     */
    int getElementCount();

    /**
     * Returns the array element value at the given index position.
     *
     * @param index index, which must be in the range [0..getElementCount()].
     * @return the corresponding element
     */
    @NonNull
    String getElement(int index);
}
