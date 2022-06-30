package com.tyron.builder.groovy.scripts;

import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.resource.TextResource;

import java.io.Serializable;

/**
 * The source for the text of a script, with some meta-info about the script.
 */
//@UsedByScanPlugin
public interface ScriptSource extends Serializable {
    /**
     * Returns the name to use for the compiled class for this script. Never returns null.
     */
    String getClassName();

    /**
     * Returns the source for this script. Never returns null.
     */
    TextResource getResource();

    /**
     * Returns the file name that is inserted into the class during compilation.  For a script with a source
     * file this is the path to the file.  Never returns null.
     */
    String getFileName();

    /**
     * Returns a long description for this script. Same as {@link #getLongDisplayName()} but here for backwards compatibility.
     */
    String getDisplayName();

    /**
     * Returns a long display name for this script. The long description should use absolute paths and assume no particular context.
     */
    DisplayName getLongDisplayName();

    /**
     * Returns a short display name for this script. The short description may use relative paths.
     */
    DisplayName getShortDisplayName();
}
