package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * A resource value representing a declare-styleable resource.
 *
 * <p>{@link #getValue()} will return null, instead use {@link #getAllAttributes()} to get the list
 * of attributes defined in the declare-styleable.
 */
public interface StyleableResourceValue extends ResourceValue {
    @NonNull
    List<AttrResourceValue> getAllAttributes();
}

