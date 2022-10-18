package com.tyron.builder.model.v2.ide

import com.tyron.builder.model.v2.AndroidModel

/**
 * Options for aapt, but only those needed by the IDE.
 *
 * @since 4.2
 */
interface AaptOptions: AndroidModel {
    enum class Namespacing {
        /**
         * Resources are not namespaced.
         *
         *
         * They are merged at the application level, as was the behavior with AAPT1
         */
        DISABLED,

        /**
         * Resources must be namespaced.
         *
         *
         * Each library is compiled in to an AAPT2 static library with its own namespace.
         *
         *
         * Projects using this *cannot* consume non-namespaced dependencies.
         */
        REQUIRED
        // TODO: add more modes as implemented.
    }

    /** Returns the resource namespacing strategy for this sub-project  */
    val namespacing: Namespacing
}
