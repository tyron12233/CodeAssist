package com.tyron.builder.api;

import java.util.Map;

/**
 * <p>An {@code AntBuilder} allows you to use Ant from your build script.</p>
 */
@SuppressWarnings("deprecation")
public abstract class AntBuilder extends groovy.util.AntBuilder {
    /**
     * Returns the properties of the Ant project. This is a live map, you that you can make changes to the map and these
     * changes are reflected in the Ant project.
     *
     * @return The properties. Never returns null.
     */
    public abstract Map<String, Object> getProperties();

    /**
     * Returns the references of the Ant project. This is a live map, you that you can make changes to the map and these
     * changes are reflected in the Ant project.
     *
     * @return The references. Never returns null.
     */
    public abstract Map<String, Object> getReferences();

    /**
     * Imports an Ant build into the associated Gradle project.
     *
     * @param antBuildFile The build file. This is resolved as per {@link Project#file(Object)}.
     */
    public abstract void importBuild(Object antBuildFile);

    /**
     * Imports an Ant build into the associated Gradle project, specifying the base directory for Gradle tasks that correspond to Ant targets.
     * <p>
     * By default the base directory is the Ant build file parent directory. The relative paths are relative to {@link Project#getProjectDir()}.
     *
     * @param antBuildFile The build file. This is resolved as per {@link Project#file(Object)}.
     * @param baseDirectory The base directory. This is resolved as per {@link Project#file(Object)}.
     *
     * @since 7.1
     */
    @Incubating
    public abstract void importBuild(Object antBuildFile, String baseDirectory);

    /**
     * Imports an Ant build into the associated Gradle project, potentially providing alternative names for Gradle tasks that correspond to Ant targets.
     * <p>
     * For each Ant target that is to be converted to a Gradle task, the given {@code taskNamer} receives the Ant target name as input
     * and is expected to return the desired name for the corresponding Gradle task.
     * The transformer may be called multiple times with the same input.
     * Implementations should ensure uniqueness of the return value for a distinct input.
     * That is, no two inputs should yield the same return value.
     *
     * @param antBuildFile The build file. This is resolved as per {@link com.tyron.builder.api.Project#file(Object)}.
     * @param taskNamer A transformer that calculates the name of the Gradle task for a corresponding Ant target.
     */
    public abstract void importBuild(Object antBuildFile, Transformer<? extends String, ? super String> taskNamer);

    /**
     * Imports an Ant build into the associated Gradle project, specifying the base directory and potentially providing alternative names
     * for Gradle tasks that correspond to Ant targets.
     * <p>
     * By default the base directory is the Ant build file parent directory. The relative paths are relative to {@link Project#getProjectDir()}.
     * <p>
     * For each Ant target that is to be converted to a Gradle task, the given {@code taskNamer} receives the Ant target name as input
     * and is expected to return the desired name for the corresponding Gradle task.
     * The transformer may be called multiple times with the same input.
     * Implementations should ensure uniqueness of the return value for a distinct input.
     * That is, no two inputs should yield the same return value.
     *
     * @param antBuildFile The build file. This is resolved as per {@link Project#file(Object)}.
     * @param baseDirectory The base directory. This is resolved as per {@link Project#file(Object)}.
     * @param taskNamer A transformer that calculates the name of the Gradle task for a corresponding Ant target.
     *
     * @since 7.1
     */
    @Incubating
    public abstract void importBuild(Object antBuildFile, String baseDirectory, Transformer<? extends String, ? super String> taskNamer);

    /**
     * Returns this AntBuilder. Useful when you need to pass this builder to methods from within closures.
     *
     * @return this
     */
    public AntBuilder getAnt() {
        return this;
    }

    /**
     * Sets the Ant message priority that should correspond to the Gradle "lifecycle" log level.  Any messages logged at this
     * priority (or more critical priority) will be logged at least at lifecycle in Gradle's logger.  If the Ant priority already maps to a
     * higher Gradle log level, it will continue to be logged at that level.
     *
     * @param logLevel The Ant log level to map to the Gradle lifecycle log level
     */
    public abstract void setLifecycleLogLevel(AntMessagePriority logLevel);

    /**
     * Sets the Ant message priority that should correspond to the Gradle "lifecycle" log level.  Any messages logged at this
     * priority (or more critical priority) will be logged at least at lifecycle in Gradle's logger.  If the Ant priority already maps to a
     * higher Gradle log level, it will continue to be logged at that level.  Acceptable values are "VERBOSE", "DEBUG", "INFO", "WARN",
     * and "ERROR".
     *
     * @param logLevel The Ant log level to map to the Gradle lifecycle log level
     */
    public void setLifecycleLogLevel(String logLevel) {
        setLifecycleLogLevel(AntMessagePriority.valueOf(logLevel));
    }

    /**
     * Returns the Ant message priority that corresponds to the Gradle "lifecycle" log level.
     *
     * @return logLevel The Ant log level that maps to the Gradle lifecycle log level
     */
    public abstract AntMessagePriority getLifecycleLogLevel();

    /**
     * Represents the normal Ant message priorities.
     */
    public enum AntMessagePriority {
        DEBUG, VERBOSE, INFO, WARN, ERROR;

        public static AntMessagePriority from(int messagePriority) {
            switch (messagePriority) {
                case org.apache.tools.ant.Project.MSG_ERR:
                    return ERROR;
                case org.apache.tools.ant.Project.MSG_WARN:
                    return WARN;
                case org.apache.tools.ant.Project.MSG_INFO:
                    return INFO;
                case org.apache.tools.ant.Project.MSG_VERBOSE:
                    return VERBOSE;
                case org.apache.tools.ant.Project.MSG_DEBUG:
                    return DEBUG;
                default:
                    throw new IllegalArgumentException();
            }
        }
    }
}
