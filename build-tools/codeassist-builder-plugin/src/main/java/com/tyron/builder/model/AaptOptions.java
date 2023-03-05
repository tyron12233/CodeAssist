package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

/**
 * Options for aapt, but only those needed by the IDE.
 */
public interface AaptOptions {
    enum Namespacing {
        /**
         * Resources are not namespaced.
         *
         * <p>They are merged at the application level, as was the behavior with AAPT1
         */
        DISABLED,
        /**
         * Resources must be namespaced.
         *
         * <p>Each library is compiled in to an AAPT2 static library with its own namespace.
         *
         * <p>Projects using this <em>cannot</em> consume non-namespaced dependencies.
         */
        REQUIRED,
        // TODO: add more modes as implemented.
    }

    /** Returns the resource namespacing strategy for this sub-project */
    @NotNull
    Namespacing getNamespacing();
}