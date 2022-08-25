package org.gradle.api.internal.artifacts.dsl.dependencies;

/**
 * Internal API for dependency creation.
 *
 * <p>
 * This is a legacy copy kept to avoid breaking Gretty.
 * It should be removed after they release a version that doesn't use this.
 * </p>
 */
public interface DependencyFactory {
    enum ClassPathNotation {
        GRADLE_API("Gradle API"),
        GRADLE_KOTLIN_DSL("Gradle Kotlin DSL"),
        GRADLE_TEST_KIT("Gradle TestKit"),
        LOCAL_GROOVY("Local Groovy");

        public final String displayName;

        ClassPathNotation(String displayName) {
            assert displayName != null : "display name cannot be null";
            this.displayName = displayName;
        }
    }
}
