package com.tyron.xml.completion.repository.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A resource value representing a declare-styleable resource.
 *
 * <p>{@link #getValue()} will return null, instead use {@link #getAllAttributes()} to get the list
 * of attributes defined in the declare-styleable.
 */
public interface StyleableResourceValue extends ResourceValue {
    @NotNull
    List<AttrResourceValue> getAllAttributes();
}

