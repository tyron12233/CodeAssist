/*
 * Copyright 2018 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.transform;

/**
 * Companion type to {@link TransformationNode} that knows how to compute extra dependent nodes aside from the to be transformed artifact.
 * Instances of this type should not be shared beyond a single transformation chain.
 *
 * @see ExtraExecutionGraphDependenciesResolverFactory
 */
public interface TransformUpstreamDependenciesResolver {
    /**
     * Returns the dependencies that should be applied to the given transformation step.
     */
    TransformUpstreamDependencies dependenciesFor(TransformationStep transformationStep);
}
