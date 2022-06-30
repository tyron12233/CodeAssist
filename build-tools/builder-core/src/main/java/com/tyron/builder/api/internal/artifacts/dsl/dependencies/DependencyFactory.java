package com.tyron.builder.api.internal.artifacts.dsl.dependencies;

import groovy.lang.Closure;
import com.tyron.builder.api.artifacts.ClientModule;
import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.ProjectDependency;

import java.util.Map;

public interface DependencyFactory {
    //for gradle distribution specific dependencies
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

    Dependency createDependency(Object dependencyNotation); //we should consider to change the return type to DirectDependency, which requires adjustment in Kotlin DSL
    DependencyConstraint createDependencyConstraint(Object dependencyNotation);
    ClientModule createModule(Object dependencyNotation, Closure configureClosure);
    ProjectDependency createProjectDependencyFromMap(ProjectFinder projectFinder, Map<? extends String, ? extends Object> map);
}
