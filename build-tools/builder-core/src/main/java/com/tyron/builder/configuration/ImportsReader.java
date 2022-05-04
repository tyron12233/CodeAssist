package com.tyron.builder.configuration;

import java.util.List;
import java.util.Map;

public interface ImportsReader {
    /**
     * Returns the list of packages that are imported by default into each Gradle build script.
     * This list is only meant for concise presentation to the user (e.g. in documentation). For
     * machine consumption, use the {@link #getSimpleNameToFullClassNamesMapping()} method, as it
     * provides a direct mapping from each simple name to the qualified name of the class to use.
     */
    String[] getImportPackages();

    /**
     * Returns a mapping from simple to qualified class name, derived from
     * the packages returned by {@link #getImportPackages()}. For historical reasons,
     * some simple name match multiple qualified names. In those cases the first match
     * should be used when resolving a name in the DSL.
     */
    Map<String, List<String>> getSimpleNameToFullClassNamesMapping();
}
