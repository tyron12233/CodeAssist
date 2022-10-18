package org.gradle.util.internal;

import javax.annotation.Nullable;

public class GroovyDependencyUtil {
    /**
     * Returns Groovy artifact group name based on the Groovy version.
     */
    public static String groovyGroupName(String version) {
        return groovyGroupName(VersionNumber.parse(version));
    }

    /**
     * Returns Groovy artifact group name based on the Groovy version.
     */
    public static String groovyGroupName(VersionNumber version) {
        return version.getMajor() >= 4
            ? "org.apache.groovy"
            : "org.codehaus.groovy";
    }

    /**
     * Returns Groovy artifact in dependency notation: ({@code group:module:version}).
     */
    public static String groovyModuleDependency(String module, String version) {
        return groovyModuleDependency(module, version, null);
    }

    /**
     * Returns Groovy artifact in dependency notation: ({@code group:module:version[:classifier]}).
     */
    public static String groovyModuleDependency(String module, String version, @Nullable String classifier) {
        return groovyModuleDependency(module, VersionNumber.parse(version), classifier);
    }

    /**
     * Returns Groovy artifact in dependency notation: ({@code group:module:version}).
     */
    public static String groovyModuleDependency(String module, VersionNumber version) {
        return groovyModuleDependency(module, version, null);
    }

    /**
     * Returns Groovy artifact in dependency notation: ({@code group:module:version[:classifier]}).
     */
    public static String groovyModuleDependency(String module, VersionNumber version, @Nullable String classifier) {
        StringBuilder dependency = new StringBuilder();
        dependency.append(groovyGroupName(version));
        dependency.append(':');
        dependency.append(module);
        dependency.append(':');
        dependency.append(version);
        if (classifier != null && !classifier.isEmpty()) {
            dependency.append(':');
            dependency.append(classifier);
        }
        return dependency.toString();
    }
}
