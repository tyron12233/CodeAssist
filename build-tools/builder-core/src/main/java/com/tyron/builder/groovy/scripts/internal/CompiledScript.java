package com.tyron.builder.groovy.scripts.internal;

import groovy.lang.Script;

public interface CompiledScript<T extends Script, D> {
    /**
     * Returns true if the `run()` method of this script is effectively empty and can be ignored.
     */
    boolean getRunDoesSomething();

    /**
     * Returns true if the script declares any methods.
     */
    boolean getHasMethods();

    Class<? extends T> loadClass();

    D getData();

    /**
     * Called when this script is reused in a new build invocation.
     */
    void onReuse();
}
