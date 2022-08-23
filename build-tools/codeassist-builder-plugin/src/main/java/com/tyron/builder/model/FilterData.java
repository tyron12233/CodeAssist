package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a split information, like its type or dimension (density, abi, language...) and
 * the filter value (like hdpi for a density split type).
 */
public interface FilterData {

    @NotNull
    String getIdentifier();

    @NotNull
    String getFilterType();
}