package com.tyron.builder.api.internal;


import com.tyron.builder.api.Describable;

public interface DisplayName extends Describable {
    /**
     * Returns a display name that can be used at the start of a sentence.
     */
    String getCapitalizedDisplayName();
}