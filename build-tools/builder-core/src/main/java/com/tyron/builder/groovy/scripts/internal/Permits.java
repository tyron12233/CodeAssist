package com.tyron.builder.groovy.scripts.internal;

import java.util.Collections;
import java.util.Set;

public class Permits {
    private static final Permits NONE = new Permits(Collections.emptySet());

    private final Set<String> allowedExtensions;

    public Permits(Set<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public static Permits none() {
        return NONE;
    }

    /**
     * Returns the list of extension names which can be used
     * in a restricted block like the "plugins" block
     */
    public Set<String> getAllowedExtensions() {
        return allowedExtensions;
    }
}
