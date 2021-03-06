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

package com.tyron.builder.api.tasks.diagnostics.internal.graph;

import com.tyron.builder.api.tasks.diagnostics.internal.graph.nodes.RenderableDependency;
import com.tyron.builder.internal.logging.text.StyledTextOutput;

public interface NodeRenderer {
    NodeRenderer NO_OP = (output, node, alreadyRendered) -> {
    };

    void renderNode(StyledTextOutput output, RenderableDependency node, boolean alreadyRendered);
}
