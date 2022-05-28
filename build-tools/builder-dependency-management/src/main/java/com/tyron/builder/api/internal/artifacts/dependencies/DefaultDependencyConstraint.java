/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.dependencies;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.MutableVersionConstraint;
import com.tyron.builder.api.artifacts.VersionConstraint;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.artifacts.DefaultModuleIdentifier;
import com.tyron.builder.api.internal.artifacts.ModuleVersionSelectorStrictSpec;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.attributes.ImmutableAttributes;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;

import javax.annotation.Nullable;

public class DefaultDependencyConstraint implements DependencyConstraintInternal {

    private final static Logger LOG = Logging.getLogger(DefaultDependencyConstraint.class);

    private final ModuleIdentifier moduleIdentifier;
    private final MutableVersionConstraint versionConstraint;

    private String reason;
    private ImmutableAttributesFactory attributesFactory;
    private AttributeContainerInternal attributes;
    private boolean force;

    public DefaultDependencyConstraint(String group, String name, String version) {
        this.moduleIdentifier = DefaultModuleIdentifier.newId(group, name);
        this.versionConstraint = new DefaultMutableVersionConstraint(version);
    }

    public static DefaultDependencyConstraint strictly(String group, String name, String strictVersion) {
        DefaultMutableVersionConstraint versionConstraint = new DefaultMutableVersionConstraint((String) null);
        versionConstraint.strictly(strictVersion);
        return new DefaultDependencyConstraint(DefaultModuleIdentifier.newId(group, name), versionConstraint);
    }

    public DefaultDependencyConstraint(ModuleIdentifier module, VersionConstraint versionConstraint) {
        this(module, new DefaultMutableVersionConstraint(versionConstraint));
    }

    private DefaultDependencyConstraint(ModuleIdentifier module, MutableVersionConstraint versionConstraint) {
        this.moduleIdentifier = module;
        this.versionConstraint = versionConstraint;
    }

    @Nullable
    @Override
    public String getGroup() {
        return moduleIdentifier.getGroup();
    }

    @Override
    public String getName() {
        return moduleIdentifier.getName();
    }

    @Override
    public String getVersion() {
        return Strings.emptyToNull(versionConstraint.getRequiredVersion());
    }

    @Override
    public AttributeContainer getAttributes() {
        return attributes == null ? ImmutableAttributes.EMPTY : attributes.asImmutable();
    }

    @Override
    public DependencyConstraint attributes(Action<? super AttributeContainer> configureAction) {
        if (attributesFactory == null) {
            warnAboutInternalApiUse();
            return this;
        }
        if (attributes == null) {
            attributes = attributesFactory.mutable();
        }
        configureAction.execute(attributes);
        return this;
    }

    private void warnAboutInternalApiUse() {
        LOG.warn("Cannot set attributes for constraint \"" + this.getGroup() + ":" + this.getName() + ":" + this.getVersion() + "\": it was probably created by a plugin using internal APIs");
    }

    public void setAttributesFactory(ImmutableAttributesFactory attributesFactory) {
        this.attributesFactory = attributesFactory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultDependencyConstraint that = (DefaultDependencyConstraint) o;
        return Objects.equal(moduleIdentifier, that.moduleIdentifier) &&
            Objects.equal(versionConstraint, that.versionConstraint) &&
            Objects.equal(attributes, that.attributes) &&
            force == that.force;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(moduleIdentifier, versionConstraint, attributes);
    }

    @Override
    public void version(Action<? super MutableVersionConstraint> configureAction) {
        configureAction.execute(versionConstraint);
    }

    @Override
    public VersionConstraint getVersionConstraint() {
        return versionConstraint;
    }

    @Override
    public boolean matchesStrictly(ModuleVersionIdentifier identifier) {
        return new ModuleVersionSelectorStrictSpec(this).test(identifier);
    }

    @Override
    public ModuleIdentifier getModule() {
        return moduleIdentifier;
    }

    @Override
    public String getReason() {
        return reason;
    }

    @Override
    public void because(String reason) {
        this.reason = reason;
    }

    @Override
    public DependencyConstraint copy() {
        DefaultDependencyConstraint constraint = new DefaultDependencyConstraint(moduleIdentifier, versionConstraint);
        constraint.reason = reason;
        constraint.attributes = attributes;
        constraint.attributesFactory = attributesFactory;
        constraint.force = force;
        return constraint;
    }

    @Override
    public String toString() {
        return "constraint " +
            moduleIdentifier + ":" + versionConstraint +
            ", attributes=" + attributes;
    }

    @Override
    public void setForce(boolean force) {
        this.force = force;
    }

    @Override
    public boolean isForce() {
        return force;
    }
}
