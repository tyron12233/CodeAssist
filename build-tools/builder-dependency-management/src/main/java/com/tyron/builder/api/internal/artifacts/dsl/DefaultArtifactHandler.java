/*
 * Copyright 2014 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.dsl;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.ConfigurablePublishArtifact;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.PublishArtifact;
import com.tyron.builder.api.artifacts.dsl.ArtifactHandler;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.deprecation.DeprecatableConfiguration;
import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.internal.metaobject.DynamicInvokeResult;
import com.tyron.builder.internal.metaobject.MethodAccess;
import com.tyron.builder.internal.metaobject.MethodMixIn;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.util.internal.ConfigureUtil;
import com.tyron.builder.util.internal.GUtil;

import java.util.Arrays;
import java.util.List;

public class DefaultArtifactHandler implements ArtifactHandler, MethodMixIn {

    private final ConfigurationContainer configurationContainer;
    private final NotationParser<Object, ConfigurablePublishArtifact> publishArtifactFactory;
    private final DynamicMethods dynamicMethods;

    public DefaultArtifactHandler(ConfigurationContainer configurationContainer, NotationParser<Object, ConfigurablePublishArtifact> publishArtifactFactory) {
        this.configurationContainer = configurationContainer;
        this.publishArtifactFactory = publishArtifactFactory;
        dynamicMethods = new DynamicMethods();
    }

    @SuppressWarnings("rawtypes")
    private PublishArtifact pushArtifact(com.tyron.builder.api.artifacts.Configuration configuration, Object notation, Closure configureClosure) {
        Action<Object> configureAction = ConfigureUtil.configureUsing(configureClosure);
        return pushArtifact(configuration, notation, configureAction);
    }

    private PublishArtifact pushArtifact(Configuration configuration, Object notation, Action<? super ConfigurablePublishArtifact> configureAction) {
        warnIfConfigurationIsDeprecated((DeprecatableConfiguration) configuration);
        ConfigurablePublishArtifact publishArtifact = publishArtifactFactory.parseNotation(notation);
        configuration.getArtifacts().add(publishArtifact);
        configureAction.execute(publishArtifact);
        return publishArtifact;
    }

    private void warnIfConfigurationIsDeprecated(DeprecatableConfiguration configuration) {
        if (configuration.isFullyDeprecated()) {
            DeprecationLogger.deprecateConfiguration(configuration.getName()).forArtifactDeclaration()
                .replaceWith(configuration.getDeclarationAlternatives())
                .willBecomeAnErrorInGradle8()
                .withUpgradeGuideSection(5, "dependencies_should_no_longer_be_declared_using_the_compile_and_runtime_configurations")
                .nagUser();
        }
    }

    @Override
    public PublishArtifact add(String configurationName, Object artifactNotation, Action<? super ConfigurablePublishArtifact> configureAction) {
        return pushArtifact(configurationContainer.getByName(configurationName), artifactNotation, configureAction);
    }

    @Override
    public PublishArtifact add(String configurationName, Object artifactNotation) {
        return pushArtifact(configurationContainer.getByName(configurationName), artifactNotation, Actions.doNothing());
    }

    @Override
    @SuppressWarnings("rawtypes")
    public PublishArtifact add(String configurationName, Object artifactNotation, Closure configureClosure) {
        return pushArtifact(configurationContainer.getByName(configurationName), artifactNotation, configureClosure);
    }

    @Override
    public MethodAccess getAdditionalMethods() {
        return dynamicMethods;
    }

    private class DynamicMethods implements MethodAccess {
        @Override
        public boolean hasMethod(String name, Object... arguments) {
            return arguments.length > 0 && configurationContainer.findByName(name) != null;
        }

        @Override
        public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
            if (arguments.length == 0) {
                return DynamicInvokeResult.notFound();
            }
            Configuration configuration = configurationContainer.findByName(name);
            if (configuration == null) {
                return DynamicInvokeResult.notFound();
            }
            List<Object> normalizedArgs = GUtil.flatten(Arrays.asList(arguments), false);
            if (normalizedArgs.size() == 2 && normalizedArgs.get(1) instanceof Closure) {
                return DynamicInvokeResult.found(pushArtifact(configuration, normalizedArgs.get(0), (Closure) normalizedArgs.get(1)));
            } else {
                for (Object notation : normalizedArgs) {
                    pushArtifact(configuration, notation, Actions.doNothing());
                }
                return DynamicInvokeResult.found();
            }
        }
    }
}
