package org.gradle.internal;


import org.gradle.api.Describable;

public interface DisplayName extends Describable {
    /**
     * Returns a display name that can be used at the start of a sentence.
     */
    String getCapitalizedDisplayName();
}