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
package com.tyron.builder.api.tasks.diagnostics;

import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.ResolveException;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.result.DependencyResult;
import com.tyron.builder.api.artifacts.result.ResolvedComponentResult;
import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.VersionConflictException;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentSelector;
import com.tyron.builder.internal.locking.LockOutOfDateException;
import com.tyron.builder.internal.logging.text.StyledTextOutput;

import java.util.List;

class ResolutionErrorRenderer implements Action<Throwable> {
    private final Spec<DependencyResult> dependencySpec;
    private final List<Action<StyledTextOutput>> errorActions = Lists.newArrayListWithExpectedSize(1);

    public ResolutionErrorRenderer(Spec<DependencyResult> dependencySpec) {
        this.dependencySpec = dependencySpec;
    }

    @Override
    public void execute(Throwable throwable) {
        if (throwable instanceof ResolveException) {
            Throwable cause = throwable.getCause();
            handleResolutionError(cause);
        } else {
            throw UncheckedException.throwAsUncheckedException(throwable);
        }
    }

    private void handleResolutionError(Throwable cause) {
        if (cause instanceof VersionConflictException) {
            handleConflict((VersionConflictException) cause);
        } else if (cause instanceof LockOutOfDateException) {
            handleOutOfDateLocks((LockOutOfDateException) cause);
        } else {
            // Fallback to failing the task in case we don't know anything special
            // about the error
            throw UncheckedException.throwAsUncheckedException(cause);
        }
    }

    private void handleOutOfDateLocks(final LockOutOfDateException cause) {
        registerError(output -> {
            List<String> errors = cause.getErrors();
            output.text("The dependency locks are out-of-date:");
            output.println();
            for (String error : errors) {
                output.text("   - " + error);
                output.println();
            }
            output.println();
        });
    }

    private void handleConflict(final VersionConflictException conflict) {
        registerError(output -> {
            output.text("Dependency resolution failed because of conflict(s) on the following module(s):");
            output.println();
            for (Pair<List<? extends ModuleVersionIdentifier>, String> identifierStringPair : conflict.getConflicts()) {
                boolean matchesSpec = hasVersionConflictOnRequestedDependency(identifierStringPair.getLeft());
                if (!matchesSpec) {
                    continue;
                }
                output.text("   - ");
                output.withStyle(StyledTextOutput.Style.Error).text(identifierStringPair.getRight());
                output.println();
            }
            output.println();
        });

    }

    public void renderErrors(StyledTextOutput output) {
        for (Action<StyledTextOutput> errorAction : errorActions) {
            errorAction.execute(output);
        }
    }

    private void registerError(Action<StyledTextOutput> errorAction) {
        errorActions.add(errorAction);
    }

    private boolean hasVersionConflictOnRequestedDependency(final List<? extends ModuleVersionIdentifier> versionIdentifiers) {
        for (final ModuleVersionIdentifier versionIdentifier : versionIdentifiers) {
            if (dependencySpec.isSatisfiedBy(asDependencyResult(versionIdentifier))) {
                return true;
            }
        }
        return false;
    }

    private DependencyResult asDependencyResult(final ModuleVersionIdentifier versionIdentifier) {
        return new DependencyResult() {
            @Override
            public ComponentSelector getRequested() {
                return DefaultModuleComponentSelector.newSelector(versionIdentifier.getModule(), versionIdentifier.getVersion());
            }

            @Override
            public ResolvedComponentResult getFrom() {
                return null;
            }

            @Override
            public boolean isConstraint() {
                return false;
            }
        };
    }

}
