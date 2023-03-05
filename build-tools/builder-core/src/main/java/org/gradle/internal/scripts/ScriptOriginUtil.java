package org.gradle.internal.scripts;

public class ScriptOriginUtil {

    /**
     * Extracts the class identifier of the value defined in a build script
     * independent of its absolute path.
     *
     * The identifier depends on the original class name and the content hash of the script.
     * As such it can be used for build caching between different locations.
     */
    public static String getOriginClassIdentifier(Object value) {
        if (value instanceof ScriptOrigin) {
            ScriptOrigin origin = (ScriptOrigin) value;
            return origin.getOriginalClassName() + "_" + origin.getContentHash();
        } else {
            return value.getClass().getName();
        }
    }
}