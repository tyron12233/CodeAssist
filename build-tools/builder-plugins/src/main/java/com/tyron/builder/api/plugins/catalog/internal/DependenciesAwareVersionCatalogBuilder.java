/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tyron.builder.api.plugins.catalog.internal;

import com.google.common.collect.Interner;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.Dependency;
import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.DependencyConstraintSet;
import com.tyron.builder.api.artifacts.DependencySet;
import com.tyron.builder.api.artifacts.ExternalModuleDependency;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.MutableVersionConstraint;
import com.tyron.builder.api.artifacts.VersionConstraint;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.DependencyResolutionServices;
import com.tyron.builder.api.internal.artifacts.ImmutableVersionConstraint;
import com.tyron.builder.api.internal.catalog.DefaultVersionCatalog;
import com.tyron.builder.api.internal.catalog.DefaultVersionCatalogBuilder;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ProviderFactory;

import javax.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.tyron.builder.api.internal.catalog.parser.DependenciesModelHelper.ALIAS_PATTERN;

public class DependenciesAwareVersionCatalogBuilder extends DefaultVersionCatalogBuilder {
    private final static Logger LOGGER = Logging.getLogger(DependenciesAwareVersionCatalogBuilder.class);

    private final Configuration dependenciesConfiguration;
    private final Map<ModuleIdentifier, String> explicitAliases = Maps.newHashMap();
    private boolean shouldAmendModel = true;

    @Inject
    public DependenciesAwareVersionCatalogBuilder(String name,
                                                  Interner<String> strings,
                                                  Interner<ImmutableVersionConstraint> versionConstraintInterner,
                                                  ObjectFactory objects,
                                                  ProviderFactory providers,
                                                  Supplier<DependencyResolutionServices> dependencyResolutionServicesSupplier,
                                                  Configuration dependenciesConfiguration) {
        super(name, strings, versionConstraintInterner, objects, providers, dependencyResolutionServicesSupplier);
        this.dependenciesConfiguration = dependenciesConfiguration;
    }

    @Override
    public DefaultVersionCatalog build() {
        if (shouldAmendModel) {
            DependencySet allDependencies = dependenciesConfiguration.getAllDependencies();
            DependencyConstraintSet allDependencyConstraints = dependenciesConfiguration.getAllDependencyConstraints();
            Set<ModuleIdentifier> seen = Sets.newHashSet();
            collectDependencies(allDependencies, seen);
            collectConstraints(allDependencyConstraints, seen);
        }
        shouldAmendModel = false;
        return super.build();
    }

    void tryGenericAlias(String group, String name, Action<? super MutableVersionConstraint> versionSpec) {
        String alias = normalizeName(name);
        if (containsDependencyAlias(alias)) {
            throw new InvalidUserDataException("A dependency with alias '" + alias + "' already exists for module '" + group + ":" + name + "'. Please configure an explicit alias for this dependency.");
        }
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new InvalidUserDataException("Unable to generate an automatic alias for '" + group + ":" + name + "'. Please configure an explicit alias for this dependency.");
        }
        alias(alias).to(group, name).version(versionSpec);
    }

    private static String normalizeName(String name) {
        return name.replace('.', '-');
    }

    private void collectDependencies(DependencySet allDependencies, Set<ModuleIdentifier> seen) {
        for (Dependency dependency : allDependencies) {
            String group = dependency.getGroup();
            String name = dependency.getName();
            if (group != null) {
                ModuleIdentifier id = DefaultModuleIdentifier.newId(group, name);
                if (seen.add(id)) {
                    String alias = explicitAliases.get(id);
                    if (alias != null) {
                        alias(alias).to(group, name).version(v -> copyDependencyVersion(dependency, group, name, v));
                    } else {
                        tryGenericAlias(group, name, v -> copyDependencyVersion(dependency, group, name, v));
                    }
                } else {
                    LOGGER.warn("Duplicate entry for dependency " + group + ":" + name);
                }
            }
        }
    }

    private static void copyDependencyVersion(Dependency dependency, String group, String name, MutableVersionConstraint v) {
        if (dependency instanceof ExternalModuleDependency) {
            VersionConstraint vc = ((ExternalModuleDependency) dependency).getVersionConstraint();
            copyConstraint(vc, v);
        } else {
            String version = dependency.getVersion();
            if (version == null || version.isEmpty()) {
                throw new InvalidUserDataException("Version for dependency " + group + ":" + name + " must not be empty");
            }
            v.require(version);
        }
    }

    private void collectConstraints(DependencyConstraintSet allConstraints, Set<ModuleIdentifier> seen) {
        for (DependencyConstraint constraint : allConstraints) {
            String group = constraint.getGroup();
            String name = constraint.getName();
            ModuleIdentifier id = DefaultModuleIdentifier.newId(group, name);
            if (seen.add(id)) {
                String alias = explicitAliases.get(id);
                if (alias != null) {
                    alias(alias).to(group, name).version(into -> copyConstraint(constraint.getVersionConstraint(), into));
                } else {
                    tryGenericAlias(group, name, into -> copyConstraint(constraint.getVersionConstraint(), into));
                }
            } else {
                LOGGER.warn("Duplicate entry for constraint " + group + ":" + name);
            }
        }
    }

    private static void copyConstraint(VersionConstraint from, MutableVersionConstraint into) {
        if (!from.getRequiredVersion().isEmpty()) {
            into.require(from.getRequiredVersion());
        }
        if (!from.getStrictVersion().isEmpty()) {
            into.strictly(from.getStrictVersion());
        }
        if (!from.getPreferredVersion().isEmpty()) {
            into.prefer(from.getPreferredVersion());
        }
        if (!from.getRejectedVersions().isEmpty()) {
            into.reject(from.getRejectedVersions().toArray(new String[0]));
        }
    }

    public void configureExplicitAlias(ModuleIdentifier id, String alias) {
        explicitAliases.put(id, alias);
    }
}
