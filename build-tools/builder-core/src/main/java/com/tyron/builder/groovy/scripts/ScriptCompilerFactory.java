package com.tyron.builder.groovy.scripts;

/**
 * A factory for script compilers.
 */
public interface ScriptCompilerFactory {
    /**
     * Creates a compiler for the given source. The returned compiler can be used to compile the script into various
     * different forms.
     *
     * @param source The script source.
     * @return a compiler which can be used to compiler the script.
     */
    ScriptCompiler createCompiler(ScriptSource source);
}
