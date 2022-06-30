package com.tyron.builder.internal.jvm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * These JVM arguments should be passed to any Gradle process that will be running on Java 9+
 * Gradle accesses those packages reflectively. On Java versions 9 to 15, the users will get
 * a warning they can do nothing about. On Java 16+, strong encapsulation of JDK internals is
 * enforced and not having the explicit permissions for reflective accesses will result in runtime exceptions.
 */
public class JpmsConfiguration {

    public static final List<String> GROOVY_JPMS_ARGS = Collections.unmodifiableList(Arrays.asList(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.prefs/java.util.prefs=ALL-UNNAMED" // required by PreferenceCleaningGroovySystemLoader
    ));

    public static final List<String> GRADLE_WORKER_JPMS_ARGS = Collections.unmodifiableList(Arrays.asList(
        "--add-opens", "java.base/java.util=ALL-UNNAMED", // required by native platform: WrapperProcess.getEnv
        "--add-opens", "java.base/java.lang=ALL-UNNAMED" // required by ClassLoaderUtils
    ));

    public static final List<String> GRADLE_DAEMON_JPMS_ARGS;

    static {
        List<String> gradleDaemonJvmArgs = new ArrayList<String>();
        gradleDaemonJvmArgs.addAll(GROOVY_JPMS_ARGS);

        List<String> configurationCacheJpmsArgs = Collections.unmodifiableList(Arrays.asList(
            "--add-opens", "java.prefs/java.util.prefs=ALL-UNNAMED", // required by JavaObjectSerializationCodec.kt
            "--add-opens", "java.base/java.nio.charset=ALL-UNNAMED", // required by BeanSchemaKt
            "--add-opens", "java.base/java.net=ALL-UNNAMED", // required by JavaObjectSerializationCodec
            "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED" // serialized from com.tyron.builder.internal.file.StatStatistics$Collector
        ));
        gradleDaemonJvmArgs.addAll(configurationCacheJpmsArgs);

        GRADLE_DAEMON_JPMS_ARGS = Collections.unmodifiableList(gradleDaemonJvmArgs);
    }
}
