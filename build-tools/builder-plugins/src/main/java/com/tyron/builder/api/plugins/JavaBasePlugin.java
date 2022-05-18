package com.tyron.builder.api.plugins;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.artifacts.type.ArtifactTypeDefinition;
import com.tyron.builder.api.internal.project.ProjectInternal;

import javax.inject.Inject;

import java.util.Set;

/**
 * <p>A {@link org.gradle.api.Plugin} which compiles and tests Java source, and assembles it into a JAR file.</p>
 *
 * @see <a href="https://docs.gradle.org/current/userguide/java_plugin.html">Java plugin reference</a>
 */
public class JavaBasePlugin implements Plugin<BuildProject> {

    public static final String CHECK_TASK_NAME = LifecycleBasePlugin.CHECK_TASK_NAME;

    public static final String VERIFICATION_GROUP = LifecycleBasePlugin.VERIFICATION_GROUP;
    public static final String BUILD_TASK_NAME = LifecycleBasePlugin.BUILD_TASK_NAME;
    public static final String BUILD_DEPENDENTS_TASK_NAME = "buildDependents";
    public static final String BUILD_NEEDED_TASK_NAME = "buildNeeded";
    public static final String DOCUMENTATION_GROUP = "documentation";

    /**
     * Set this property to use JARs build from subprojects, instead of the classes folder from these project, on the compile classpath.
     * The main use case for this is to mitigate performance issues on very large multi-projects building on Windows.
     * Setting this property will cause the 'jar' task of all subprojects in the dependency tree to always run during compilation.
     *
     * @since 5.6
     */
    public static final String COMPILE_CLASSPATH_PACKAGING_SYSTEM_PROPERTY = "org.gradle.java.compile-classpath-packaging";

    /**
     * A list of known artifact types which are known to prevent from
     * publication.
     *
     * @since 5.3
     */
    public static final Set<String> UNPUBLISHABLE_VARIANT_ARTIFACTS = ImmutableSet.of(
            ArtifactTypeDefinition.JVM_CLASS_DIRECTORY,
            ArtifactTypeDefinition.JVM_RESOURCES_DIRECTORY,
            ArtifactTypeDefinition.DIRECTORY_TYPE
    );

    private final boolean javaClasspathPackaging;
//    private final JvmPluginServices jvmPluginServices;

    @Inject
    public JavaBasePlugin(
//            JvmEcosystemUtilities jvmPluginServices
    ) {
        this.javaClasspathPackaging = Boolean.getBoolean(COMPILE_CLASSPATH_PACKAGING_SYSTEM_PROPERTY);
//        this.jvmPluginServices = (JvmPluginServices) jvmPluginServices;
    }
    @Override
    public void apply(BuildProject project) {
        ProjectInternal projectInternal = (ProjectInternal) project;

        project.getPluginManager().apply(BasePlugin.class);
        project.getPluginManager().apply(JvmEcosystemPlugin.class);
        project.getPluginManager().apply(ReportingBasePlugin.class);

        DefaultJavaPluginExtension javaPluginExtension = addExtensions(projectInternal);

        configureSourceSetDefaults(project, javaPluginExtension);
        configureCompileDefaults(project, javaPluginExtension);

        configureJavaDoc(project, javaPluginExtension);
        configureTest(project, javaPluginExtension);
        configureBuildNeeded(project);
        configureBuildDependents(project);
    }
}
