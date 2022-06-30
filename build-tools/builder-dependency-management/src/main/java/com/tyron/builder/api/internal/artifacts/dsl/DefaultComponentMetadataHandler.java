/*
 * Copyright 2013 the original author or authors.
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

import com.google.common.collect.Interner;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.PlatformSupport;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.DependencyConstraintMetadataImpl;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.DirectDependencyMetadataImpl;
import com.tyron.builder.internal.component.external.model.VariantDerivationStrategy;
import com.tyron.builder.internal.rules.DefaultRuleActionAdapter;
import com.tyron.builder.internal.rules.DefaultRuleActionValidator;
import com.tyron.builder.internal.rules.RuleAction;
import com.tyron.builder.internal.rules.RuleActionAdapter;
import com.tyron.builder.internal.rules.RuleActionValidator;
import com.tyron.builder.internal.rules.SpecRuleAction;

import groovy.lang.Closure;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.ActionConfiguration;
import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.artifacts.ComponentMetadataContext;
import com.tyron.builder.api.artifacts.ComponentMetadataDetails;
import com.tyron.builder.api.artifacts.ComponentMetadataRule;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler;
import com.tyron.builder.api.artifacts.ivy.IvyModuleDescriptor;
import com.tyron.builder.api.artifacts.maven.PomModuleDescriptor;
import com.tyron.builder.api.internal.artifacts.ComponentMetadataProcessor;
import com.tyron.builder.api.internal.artifacts.ComponentMetadataProcessorFactory;
import com.tyron.builder.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import com.tyron.builder.api.internal.artifacts.MetadataResolutionContext;

import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.notations.ComponentIdentifierParserFactory;
import com.tyron.builder.api.internal.notations.DependencyMetadataNotationParser;
import com.tyron.builder.api.internal.notations.ModuleIdentifierNotationConverter;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.api.specs.Specs;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.action.ConfigurableRule;
import com.tyron.builder.internal.action.DefaultConfigurableRule;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.management.DependencyResolutionManagementInternal;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resolve.caching.ComponentMetadataRuleExecutor;

import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.typeconversion.NotationParserBuilder;
import com.tyron.builder.internal.typeconversion.UnsupportedNotationException;
import com.tyron.builder.util.Predicates;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class DefaultComponentMetadataHandler implements ComponentMetadataHandler, ComponentMetadataHandlerInternal {
    private static final String ADAPTER_NAME = ComponentMetadataHandler.class.getSimpleName();
    private static final String INVALID_SPEC_ERROR = "Could not add a component metadata rule for module '%s'.";

    private final Instantiator instantiator;
    private final ComponentMetadataRuleContainer metadataRuleContainer;
    private final RuleActionAdapter ruleActionAdapter;
    private final NotationParser<Object, ModuleIdentifier> moduleIdentifierNotationParser;
    private final NotationParser<Object, DirectDependencyMetadataImpl> dependencyMetadataNotationParser;
    private final NotationParser<Object, DependencyConstraintMetadataImpl> dependencyConstraintMetadataNotationParser;
    private final NotationParser<Object, ComponentIdentifier> componentIdentifierNotationParser;
    private final ImmutableAttributesFactory attributesFactory;
    private final IsolatableFactory isolatableFactory;
    private final ComponentMetadataRuleExecutor ruleExecutor;
    private final PlatformSupport platformSupport;

    DefaultComponentMetadataHandler(Instantiator instantiator,
                                    RuleActionAdapter ruleActionAdapter,
                                    ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                                    Interner<String> stringInterner,
                                    ImmutableAttributesFactory attributesFactory,
                                    IsolatableFactory isolatableFactory,
                                    ComponentMetadataRuleExecutor ruleExecutor,
                                    PlatformSupport platformSupport) {
        this.instantiator = instantiator;
        this.ruleActionAdapter = ruleActionAdapter;
        this.moduleIdentifierNotationParser = NotationParserBuilder
            .toType(ModuleIdentifier.class)
            .fromCharSequence(new ModuleIdentifierNotationConverter(moduleIdentifierFactory))
            .toComposite();
        this.ruleExecutor = ruleExecutor;
        this.dependencyMetadataNotationParser = DependencyMetadataNotationParser.parser(instantiator, DirectDependencyMetadataImpl.class, stringInterner);
        this.dependencyConstraintMetadataNotationParser = DependencyMetadataNotationParser.parser(instantiator, DependencyConstraintMetadataImpl.class, stringInterner);
        this.componentIdentifierNotationParser = new ComponentIdentifierParserFactory().create();
        this.attributesFactory = attributesFactory;
        this.isolatableFactory = isolatableFactory;
        this.metadataRuleContainer = new ComponentMetadataRuleContainer();
        this.platformSupport = platformSupport;
    }

    public DefaultComponentMetadataHandler(Instantiator instantiator, ImmutableModuleIdentifierFactory moduleIdentifierFactory, Interner<String> stringInterner, ImmutableAttributesFactory attributesFactory, IsolatableFactory isolatableFactory, ComponentMetadataRuleExecutor ruleExecutor, PlatformSupport platformSupport) {
        this(instantiator, createAdapter(), moduleIdentifierFactory, stringInterner, attributesFactory, isolatableFactory, ruleExecutor, platformSupport);
    }

    private DefaultComponentMetadataHandler(Instantiator instantiator,
                                            RuleActionAdapter ruleActionAdapter,
                                            NotationParser<Object, ModuleIdentifier> moduleIdentifierNotationParser,
                                            NotationParser<Object, DirectDependencyMetadataImpl> dependencyMetadataNotationParser,
                                            NotationParser<Object, DependencyConstraintMetadataImpl> dependencyConstraintMetadataNotationParser,
                                            NotationParser<Object, ComponentIdentifier> componentIdentifierNotationParser,
                                            ImmutableAttributesFactory attributesFactory,
                                            IsolatableFactory isolatableFactory,
                                            ComponentMetadataRuleExecutor ruleExecutor,
                                            PlatformSupport platformSupport) {
        this.instantiator = instantiator;
        this.ruleActionAdapter = ruleActionAdapter;
        this.moduleIdentifierNotationParser = moduleIdentifierNotationParser;
        this.ruleExecutor = ruleExecutor;
        this.dependencyMetadataNotationParser = dependencyMetadataNotationParser;
        this.dependencyConstraintMetadataNotationParser = dependencyConstraintMetadataNotationParser;
        this.componentIdentifierNotationParser = componentIdentifierNotationParser;
        this.attributesFactory = attributesFactory;
        this.isolatableFactory = isolatableFactory;
        this.metadataRuleContainer = new ComponentMetadataRuleContainer();
        this.platformSupport = platformSupport;
    }

    private static RuleActionAdapter createAdapter() {
        RuleActionValidator ruleActionValidator = new DefaultRuleActionValidator(IvyModuleDescriptor.class, PomModuleDescriptor.class);
        return new DefaultRuleActionAdapter(ruleActionValidator, ADAPTER_NAME);
    }

    private ComponentMetadataHandler addRule(SpecRuleAction<? super ComponentMetadataDetails> ruleAction) {
        metadataRuleContainer.addRule(ruleAction);
        return this;
    }

    private ComponentMetadataHandler addClassBasedRule(SpecConfigurableRule ruleAction) {
        metadataRuleContainer.addClassRule(ruleAction);
        return this;
    }

    private <U> SpecRuleAction<? super U> createAllSpecRuleAction(RuleAction<? super U> ruleAction) {
        return new SpecRuleAction<>(ruleAction, Predicates.satisfyAll());
    }

    private SpecRuleAction<? super ComponentMetadataDetails> createSpecRuleActionForModule(Object id, RuleAction<? super ComponentMetadataDetails> ruleAction) {
        ModuleIdentifier moduleIdentifier;

        try {
            moduleIdentifier = moduleIdentifierNotationParser.parseNotation(id);
        } catch (UnsupportedNotationException e) {
            throw new InvalidUserCodeException(String.format(INVALID_SPEC_ERROR, id == null ? "null" : id.toString()), e);
        }

        Predicate<ComponentMetadataDetails> spec = new ComponentMetadataDetailsMatchingSpec(moduleIdentifier);
        return new SpecRuleAction<>(ruleAction, spec);
    }

    @Override
    public ComponentMetadataHandler all(Action<? super ComponentMetadataDetails> rule) {
        return addRule(createAllSpecRuleAction(ruleActionAdapter.createFromAction(rule)));
    }

    @Override
    public ComponentMetadataHandler all(Closure<?> rule) {
        return addRule(createAllSpecRuleAction(ruleActionAdapter.createFromClosure(ComponentMetadataDetails.class, rule)));
    }

    @Override
    public ComponentMetadataHandler all(Object ruleSource) {
        return addRule(createAllSpecRuleAction(ruleActionAdapter.createFromRuleSource(ComponentMetadataDetails.class, ruleSource)));
    }

    @Override
    public ComponentMetadataHandler withModule(Object id, Action<? super ComponentMetadataDetails> rule) {
        return addRule(createSpecRuleActionForModule(id, ruleActionAdapter.createFromAction(rule)));
    }

    @Override
    public ComponentMetadataHandler withModule(Object id, Closure<?> rule) {
        return addRule(createSpecRuleActionForModule(id, ruleActionAdapter.createFromClosure(ComponentMetadataDetails.class, rule)));
    }

    @Override
    public ComponentMetadataHandler withModule(Object id, Object ruleSource) {
        return addRule(createSpecRuleActionForModule(id, ruleActionAdapter.createFromRuleSource(ComponentMetadataDetails.class, ruleSource)));
    }

    @Override
    public ComponentMetadataHandler all(Class<? extends ComponentMetadataRule> rule) {
        return addClassBasedRule(createAllSpecConfigurableRule(DefaultConfigurableRule.of(rule)));
    }

    @Override
    public ComponentMetadataHandler all(Class<? extends ComponentMetadataRule> rule, Action<? super ActionConfiguration> configureAction) {
        return addClassBasedRule(createAllSpecConfigurableRule(DefaultConfigurableRule.of(rule, configureAction, isolatableFactory)));
    }

    @Override
    public ComponentMetadataHandler withModule(Object id, Class<? extends ComponentMetadataRule> rule) {
        return addClassBasedRule(createModuleSpecConfigurableRule(id, DefaultConfigurableRule.of(rule)));
    }

    @Override
    public ComponentMetadataHandler withModule(Object id, Class<? extends ComponentMetadataRule> rule, Action<? super ActionConfiguration> configureAction) {
        return addClassBasedRule(createModuleSpecConfigurableRule(id, DefaultConfigurableRule.of(rule, configureAction, isolatableFactory)));
    }

    private SpecConfigurableRule createModuleSpecConfigurableRule(Object id, ConfigurableRule<ComponentMetadataContext> instantiatingAction) {
        ModuleIdentifier moduleIdentifier;

        try {
            moduleIdentifier = moduleIdentifierNotationParser.parseNotation(id);
        } catch (UnsupportedNotationException e) {
            throw new InvalidUserCodeException(String.format(INVALID_SPEC_ERROR, id == null ? "null" : id.toString()), e);
        }

        Predicate<ModuleVersionIdentifier> spec = new ModuleVersionIdentifierSpec(moduleIdentifier);
        return new SpecConfigurableRule(instantiatingAction, spec);
    }

    private SpecConfigurableRule createAllSpecConfigurableRule(ConfigurableRule<ComponentMetadataContext> instantiatingAction) {
        return new SpecConfigurableRule(instantiatingAction, Predicates.satisfyAll());
    }

    @Override
    public ComponentMetadataProcessor createComponentMetadataProcessor(MetadataResolutionContext resolutionContext) {
        return new DefaultComponentMetadataProcessor(metadataRuleContainer, instantiator, dependencyMetadataNotationParser, dependencyConstraintMetadataNotationParser, componentIdentifierNotationParser, attributesFactory, ruleExecutor, platformSupport, resolutionContext);
    }

    @Override
    public void setVariantDerivationStrategy(VariantDerivationStrategy strategy) {
        metadataRuleContainer.setVariantDerivationStrategy(strategy);
    }

    @Override
    public VariantDerivationStrategy getVariantDerivationStrategy() {
        return metadataRuleContainer.getVariantDerivationStrategy();
    }

    @Override
    public void onAddRule(Consumer<DisplayName> consumer) {
        metadataRuleContainer.onAddRule(consumer);
    }

    @Override
    public ComponentMetadataProcessorFactory createFactory(DependencyResolutionManagementInternal dependencyResolutionManagement) {
        // we need to defer the creation of the actual factory until configuration is completed
        // Typically the state of whether to prefer project rules or not is not known when this
        // method is called.
        Supplier<ComponentMetadataHandlerInternal> actualHandler = () -> {
            // determine whether to use the project local handler or the settings handler
            boolean useRules = dependencyResolutionManagement.getConfiguredRulesMode().useProjectRules();
            if (metadataRuleContainer.isEmpty() || !useRules) {
                // We're creating a component metadata handler which will be applied the settings
                // rules and the current derivation strategy
                DefaultComponentMetadataHandler delegate = new DefaultComponentMetadataHandler(
                    instantiator, ruleActionAdapter, moduleIdentifierNotationParser, dependencyMetadataNotationParser, dependencyConstraintMetadataNotationParser, componentIdentifierNotationParser, attributesFactory, isolatableFactory, ruleExecutor, platformSupport
                );
                dependencyResolutionManagement.applyRules(delegate);
                delegate.setVariantDerivationStrategy(getVariantDerivationStrategy());
                return delegate;
            }
            return this;
        };
        return resolutionContext -> actualHandler.get().createComponentMetadataProcessor(resolutionContext);
    }

    static class ComponentMetadataDetailsMatchingSpec implements Predicate<ComponentMetadataDetails> {
        private final ModuleIdentifier target;

        ComponentMetadataDetailsMatchingSpec(ModuleIdentifier target) {
            this.target = target;
        }

        @Override
        public boolean test(ComponentMetadataDetails componentMetadataDetails) {
            ModuleVersionIdentifier identifier = componentMetadataDetails.getId();
            return identifier.getGroup().equals(target.getGroup()) && identifier.getName().equals(target.getName());
        }
    }

    static class ModuleVersionIdentifierSpec implements Predicate<ModuleVersionIdentifier> {
        private final ModuleIdentifier target;

        ModuleVersionIdentifierSpec(ModuleIdentifier target) {
            this.target = target;
        }

        @Override
        public boolean test(ModuleVersionIdentifier identifier) {
            return identifier.getGroup().equals(target.getGroup()) && identifier.getName().equals(target.getName());
        }
    }

}
