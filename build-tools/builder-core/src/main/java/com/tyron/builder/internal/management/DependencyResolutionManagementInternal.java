package com.tyron.builder.internal.management;

import com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler;
import com.tyron.builder.api.initialization.dsl.VersionCatalogBuilder;
import com.tyron.builder.api.initialization.resolve.DependencyResolutionManagement;
import com.tyron.builder.api.initialization.resolve.RepositoriesMode;
import com.tyron.builder.api.initialization.resolve.RulesMode;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.provider.Property;

import java.util.List;

public interface DependencyResolutionManagementInternal extends DependencyResolutionManagement {

    void configureProject(ProjectInternal project);

    void preventFromFurtherMutation();

    void applyRules(ComponentMetadataHandler target);

    RepositoriesModeInternal getConfiguredRepositoriesMode();

    RulesModeInternal getConfiguredRulesMode();

    Property<String> getDefaultProjectsExtensionName();

    List<VersionCatalogBuilder> getDependenciesModelBuilders();

    enum RepositoriesModeInternal {
        PREFER_PROJECT(true),
        PREFER_SETTINGS(false),
        FAIL_ON_PROJECT_REPOS(false);

        private final boolean useProjectRepositories;

        RepositoriesModeInternal(boolean useProjectRepositories) {
            this.useProjectRepositories = useProjectRepositories;
        }
        public boolean useProjectRepositories() {
            return useProjectRepositories;
        }

        public static RepositoriesModeInternal of(RepositoriesMode mode) {
            return RepositoriesModeInternal.valueOf(mode.name());
        }
    }

    enum RulesModeInternal {
        PREFER_PROJECT(true),
        PREFER_SETTINGS(false),
        FAIL_ON_PROJECT_RULES(false);

        private final boolean useProjectRules;

        RulesModeInternal(boolean useProjectRules) {
            this.useProjectRules = useProjectRules;
        }
        public boolean useProjectRules() {
            return useProjectRules;
        }

        public static RulesModeInternal of(RulesMode mode) {
            return RulesModeInternal.valueOf(mode.name());
        }
    }
}
