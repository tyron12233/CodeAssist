package com.tyron.builder.groovy.scripts.internal;

/**
 * Data extracted from a build script at compile time.
 */
public class BuildScriptData {
    private final boolean hasImperativeStatements;

    public BuildScriptData(boolean hasImperativeStatements) {
        this.hasImperativeStatements = hasImperativeStatements;
    }

    /**
     * Returns true when the build script contains legacy imperative statements. When false, the script contains only model rule statements and its execution
     * can be deferred until rule execution is required.
     */
    public boolean getHasImperativeStatements() {
        return hasImperativeStatements;
    }
}
