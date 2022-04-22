package com.tyron.builder.internal.scripts;

import com.tyron.builder.internal.service.scopes.Scope;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.io.File;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Resolves script files according to available {@link ScriptingLanguage} providers.
 *
 * @since 4.0
 */
@ServiceScope(Scope.Global.class)
public interface ScriptFileResolver {

    /**
     * Resolves a script file.
     *
     * @param dir the directory in which to search
     * @param basename the base name of the script file, i.e. its file name excluding the extension
     * @return the resolved script file present on disk, or {@literal null} if none were found
     */
    @Nullable
    File resolveScriptFile(File dir, String basename);

    /**
     * Searches for script files in the given directory, that is, any file with a known
     * {@link ScriptingLanguage#getExtension() extension}.
     *
     * @param dir the directory in which to search
     *
     * @return list containing all script files found in the given directory in no particular order
     *
     * @since 4.6
     */
    List<File> findScriptsIn(File dir);
}