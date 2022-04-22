package com.tyron.builder.scripts;

/**
 * Scripting language provider metadata.
 *
 * @since 4.0
 */
public interface ScriptingLanguage {

    /**
     * Returns the file extension (including the leading dot) for scripts written in this scripting language.
     */
    String getExtension();

    /**
     * Returns the fully qualified class name of the scripting language provider for this scripting language.
     *
     * Implementations can benefit from injection using {@link javax.inject.Inject}.
     */
    String getProvider();

}