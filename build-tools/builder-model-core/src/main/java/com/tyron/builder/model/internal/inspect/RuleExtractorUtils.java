/*
 * Copyright 2016 the original author or authors.
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

package com.tyron.builder.model.internal.inspect;

import com.tyron.builder.model.internal.core.ModelAction;
import com.tyron.builder.model.internal.core.ModelActionRole;
import com.tyron.builder.model.internal.core.ModelPath;
import com.tyron.builder.model.internal.core.ModelSpec;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.registry.ModelReferenceNode;
import com.tyron.builder.model.internal.registry.ModelRegistry;

import javax.annotation.Nullable;

public class RuleExtractorUtils {
    public static void configureRuleAction(MethodModelRuleApplicationContext context, RuleApplicationScope ruleApplicationScope, ModelActionRole role, MethodRuleAction ruleAction) {
        ModelAction action = context.contextualize(ruleAction);
        ModelRegistry registry = context.getRegistry();
        switch (ruleApplicationScope) {
            case SELF:
                registry.configure(role, action);
                break;
            case DESCENDANTS:
                registry.configureMatching(new NonReferenceDescendantsSpec(context.getScope()), role, action);
                break;
            default:
                throw new AssertionError();
        }
    }

    private static class NonReferenceDescendantsSpec extends ModelSpec {
        private final ModelPath scope;

        private NonReferenceDescendantsSpec(ModelPath scope) {
            this.scope = scope;
        }

        @Nullable
        @Override
        public ModelPath getAncestor() {
            return scope;
        }

        @Override
        public boolean matches(MutableModelNode node) {
            return !(node instanceof ModelReferenceNode);
        }
    };
}
