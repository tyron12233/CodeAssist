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

package com.tyron.builder.platform.base.internal.registry;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.language.base.plugins.ComponentModelBasePlugin;
import com.tyron.builder.model.internal.core.ModelActionRole;
import com.tyron.builder.model.internal.core.ModelReference;
import com.tyron.builder.model.internal.core.ModelView;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.inspect.AbstractExtractedModelRule;
import com.tyron.builder.model.internal.inspect.ExtractedModelRule;
import com.tyron.builder.model.internal.inspect.MethodModelRuleApplicationContext;
import com.tyron.builder.model.internal.inspect.MethodModelRuleExtractionContext;
import com.tyron.builder.model.internal.inspect.MethodRuleDefinition;
import com.tyron.builder.model.internal.inspect.ModelRuleInvoker;
import com.tyron.builder.model.internal.inspect.RuleApplicationScope;
import com.tyron.builder.model.internal.inspect.RuleExtractorUtils;
import com.tyron.builder.model.internal.inspect.RuleSourceValidationProblemCollector;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.platform.base.BinarySpec;
import com.tyron.builder.platform.base.ComponentBinaries;
import com.tyron.builder.platform.base.VariantComponentSpec;

import javax.annotation.Nullable;
import java.util.List;

public class ComponentBinariesModelRuleExtractor extends AbstractAnnotationDrivenComponentModelRuleExtractor<ComponentBinaries> {
    private static final ModelType<BinarySpec> BINARY_SPEC = ModelType.of(BinarySpec.class);
    private static final ModelType<VariantComponentSpec> COMPONENT_SPEC = ModelType.of(VariantComponentSpec.class);

    @Nullable
    @Override
    public <R, S> ExtractedModelRule registration(MethodRuleDefinition<R, S> ruleDefinition, MethodModelRuleExtractionContext context) {
        return createRegistration(ruleDefinition, context);
    }

    private <R, S extends BinarySpec, C extends VariantComponentSpec> ExtractedModelRule createRegistration(final MethodRuleDefinition<R, ?> ruleDefinition, RuleSourceValidationProblemCollector problems) {
        RuleMethodDataCollector dataCollector = new RuleMethodDataCollector();
        visitAndVerifyMethodSignature(dataCollector, ruleDefinition, problems);
        if (problems.hasProblems()) {
            return null;
        }

        ModelType<S> binaryType = dataCollector.getParameterType(BINARY_SPEC);
        ModelType<C> componentType = dataCollector.getParameterType(COMPONENT_SPEC);
        return new ExtractedComponentBinariesRule<S, C>(componentType, binaryType, ruleDefinition);
    }

    private void visitAndVerifyMethodSignature(RuleMethodDataCollector dataCollector, MethodRuleDefinition<?, ?> ruleDefinition, RuleSourceValidationProblemCollector problems) {
        validateIsVoidMethod(ruleDefinition, problems);
        visitSubject(dataCollector, ruleDefinition, BINARY_SPEC, problems);
        visitDependency(dataCollector, ruleDefinition, ModelType.of(VariantComponentSpec.class), problems);
    }

    private static class ComponentBinariesRule<S extends BinarySpec, C extends VariantComponentSpec> extends ModelMapBasedRule<VariantComponentSpec, C> {
        private final ModelType<S> binaryType;

        public ComponentBinariesRule(ModelReference<C> subject, ModelType<C> componentType, ModelType<S> binaryType, MethodRuleDefinition<?, ?> ruleDefinition) {
            super(subject, componentType, ruleDefinition);
            this.binaryType = binaryType;
        }

        @Override
        protected void execute(ModelRuleInvoker<?> invoker, C component, List<ModelView<?>> inputs) {
            invoke(invoker, inputs, component.getBinaries().withType(binaryType.getConcreteClass()), component);
        }
    }

    private static class ExtractedComponentBinariesRule<S extends BinarySpec, C extends VariantComponentSpec> extends AbstractExtractedModelRule {
        private final ModelType<C> componentType;
        private final ModelType<S> binaryType;

        public ExtractedComponentBinariesRule(ModelType<C> componentType, ModelType<S> binaryType, MethodRuleDefinition<?, ?> ruleDefinition) {
            super(ruleDefinition);
            this.componentType = componentType;
            this.binaryType = binaryType;
        }

        @Override
        public void apply(MethodModelRuleApplicationContext context, MutableModelNode target) {
            ModelReference<C> subject = ModelReference.of(componentType);
            ComponentBinariesRule<S, C> componentBinariesRule = new ComponentBinariesRule<S, C>(subject, componentType, binaryType, getRuleDefinition());
            RuleExtractorUtils.configureRuleAction(context, RuleApplicationScope.DESCENDANTS, ModelActionRole.Finalize, componentBinariesRule);
        }

        @Override
        public List<? extends Class<?>> getRuleDependencies() {
            return ImmutableList.of(ComponentModelBasePlugin.class);
        }
    }
}
