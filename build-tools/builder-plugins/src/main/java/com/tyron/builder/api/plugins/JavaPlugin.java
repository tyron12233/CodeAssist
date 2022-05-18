package com.tyron.builder.api.plugins;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.model.ObjectFactory;

import javax.inject.Inject;

public class JavaPlugin implements Plugin<BuildProject> {

    /**
     * The name of the task that processes resources.
     */
    public static final String PROCESS_RESOURCES_TASK_NAME = "processResources";

    /**
     * The name of the lifecycle task which outcome is that all the classes of a component are generated.
     */
    public static final String CLASSES_TASK_NAME = "classes";

    /**
     * The name of the task which compiles Java sources.
     */
    public static final String COMPILE_JAVA_TASK_NAME = "compileJava";

    /**
     * The name of the task which processes the test resources.
     */
    public static final String PROCESS_TEST_RESOURCES_TASK_NAME = "processTestResources";

    /**
     * The name of the lifecycle task which outcome is that all test classes of a component are generated.
     */
    public static final String TEST_CLASSES_TASK_NAME = "testClasses";

    /**
     * The name of the task which compiles the test Java sources.
     */
    public static final String COMPILE_TEST_JAVA_TASK_NAME = "compileTestJava";

    /**
     * The name of the task which triggers execution of tests.
     */
    public static final String TEST_TASK_NAME = "test";

    /**
     * The name of the task which generates the component main jar.
     */
    public static final String JAR_TASK_NAME = "jar";

    /**
     * The name of the task which generates the component javadoc.
     */
    public static final String JAVADOC_TASK_NAME = "javadoc";

    /**
     * The name of the API configuration, where dependencies exported by a component at compile time should
     * be declared.
     *
     * @since 3.4
     */
    public static final String API_CONFIGURATION_NAME = "api";

    /**
     * The name of the implementation configuration, where dependencies that are only used internally by
     * a component should be declared.
     *
     * @since 3.4
     */
    public static final String IMPLEMENTATION_CONFIGURATION_NAME = "implementation";

    /**
     * The name of the configuration to define the API elements of a component.
     * That is, the dependencies which are required to compile against that component.
     *
     * @since 3.4
     */
    public static final String API_ELEMENTS_CONFIGURATION_NAME = "apiElements";

    /**
     * The name of the configuration that is used to declare dependencies which are only required to compile a component,
     * but not at runtime.
     */
    public static final String COMPILE_ONLY_CONFIGURATION_NAME = "compileOnly";

    /**
     * The name of the configuration to define the API elements of a component that are required to compile a component,
     * but not at runtime.
     *
     * @since 6.7
     */
    public static final String COMPILE_ONLY_API_CONFIGURATION_NAME = "compileOnlyApi";

    /**
     * The name of the runtime only dependencies configuration, used to declare dependencies
     * that should only be found at runtime.
     *
     * @since 3.4
     */
    public static final String RUNTIME_ONLY_CONFIGURATION_NAME = "runtimeOnly";

    /**
     * The name of the runtime classpath configuration, used by a component to query its own runtime classpath.
     *
     * @since 3.4
     */
    public static final String RUNTIME_CLASSPATH_CONFIGURATION_NAME = "runtimeClasspath";

    /**
     * The name of the runtime elements configuration, that should be used by consumers
     * to query the runtime dependencies of a component.
     *
     * @since 3.4
     */
    public static final String RUNTIME_ELEMENTS_CONFIGURATION_NAME = "runtimeElements";

    /**
     * The name of the javadoc elements configuration.
     *
     * @since 6.0
     */
    public static final String JAVADOC_ELEMENTS_CONFIGURATION_NAME = "javadocElements";

    /**
     * The name of the sources elements configuration.
     *
     * @since 6.0
     */
    public static final String SOURCES_ELEMENTS_CONFIGURATION_NAME = "sourcesElements";

    /**
     * The name of the compile classpath configuration.
     *
     * @since 3.4
     */
    public static final String COMPILE_CLASSPATH_CONFIGURATION_NAME = "compileClasspath";

    /**
     * The name of the annotation processor configuration.
     *
     * @since 4.6
     */
    public static final String ANNOTATION_PROCESSOR_CONFIGURATION_NAME = "annotationProcessor";

    /**
     * The name of the test implementation dependencies configuration.
     *
     * @since 3.4
     */
    public static final String TEST_IMPLEMENTATION_CONFIGURATION_NAME = "testImplementation";

    /**
     * The name of the configuration that should be used to declare dependencies which are only required
     * to compile the tests, but not when running them.
     */
    public static final String TEST_COMPILE_ONLY_CONFIGURATION_NAME = "testCompileOnly";

    /**
     * The name of the test runtime only dependencies configuration.
     *
     * @since 3.4
     */
    public static final String TEST_RUNTIME_ONLY_CONFIGURATION_NAME = "testRuntimeOnly";

    /**
     * The name of the test compile classpath configuration.
     *
     * @since 3.4
     */
    public static final String TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME = "testCompileClasspath";

    /**
     * The name of the test annotation processor configuration.
     *
     * @since 4.6
     */
    public static final String TEST_ANNOTATION_PROCESSOR_CONFIGURATION_NAME = "testAnnotationProcessor";

    /**
     * The name of the test runtime classpath configuration.
     *
     * @since 3.4
     */
    public static final String TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME = "testRuntimeClasspath";

    private static final String SOURCE_ELEMENTS_VARIANT_NAME = "mainSourceElements";

    private final ObjectFactory objectFactory;
//    private final SoftwareComponentFactory softwareComponentFactory;
//    private final JvmPluginServices jvmServices;

    @Inject
    public JavaPlugin(
            ObjectFactory objectFactory
//            SoftwareComponentFactory softwareComponentFactory,
//            JvmPluginServices jvmServices
    ) {
        this.objectFactory = objectFactory;
//        this.softwareComponentFactory = softwareComponentFactory;
//        this.jvmServices = jvmServices;
    }

    @Override
    public void apply(BuildProject project) {
        if (project.getPluginManager().hasPlugin("java-platform")) {
            throw new IllegalStateException("The \"java\" or \"java-library\" plugin cannot be applied together with the \"java-platform\" plugin. " +
                                            "A project is either a platform or a library but cannot be both at the same time.");
        }

        final ProjectInternal projectInternal = (ProjectInternal) project;

        project.getPluginManager().apply(JavaBasePlugin.class);
        project.getPluginManager().apply("org.gradle.jvm-test-suite");
    }
}
