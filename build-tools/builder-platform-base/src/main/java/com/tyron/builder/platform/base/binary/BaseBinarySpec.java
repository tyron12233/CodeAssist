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

package com.tyron.builder.platform.base.binary;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.artifacts.component.LibraryBinaryIdentifier;
import com.tyron.builder.api.internal.AbstractBuildableComponentSpec;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
import com.tyron.builder.api.reflect.ObjectInstantiationException;
import com.tyron.builder.internal.component.local.model.DefaultLibraryBinaryIdentifier;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.language.base.LanguageSourceSet;
import com.tyron.builder.model.ModelMap;
import com.tyron.builder.model.internal.core.ModelActionRole;
import com.tyron.builder.model.internal.core.ModelMaps;
import com.tyron.builder.model.internal.core.ModelRegistration;
import com.tyron.builder.model.internal.core.ModelRegistrations;
import com.tyron.builder.model.internal.core.MutableModelNode;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;
import com.tyron.builder.model.internal.core.UnmanagedModelProjection;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.platform.base.BinarySpec;
import com.tyron.builder.platform.base.BinaryTasksCollection;
import com.tyron.builder.platform.base.ComponentSpec;
import com.tyron.builder.platform.base.ModelInstantiationException;
import com.tyron.builder.platform.base.internal.BinaryBuildAbility;
import com.tyron.builder.platform.base.internal.BinaryNamingScheme;
import com.tyron.builder.platform.base.internal.BinarySpecInternal;
import com.tyron.builder.platform.base.internal.ComponentSpecIdentifier;
import com.tyron.builder.platform.base.internal.DefaultBinaryNamingScheme;
import com.tyron.builder.platform.base.internal.DefaultBinaryTasksCollection;
import com.tyron.builder.platform.base.internal.FixedBuildAbility;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Set;

/**
 * Base class that may be used for custom {@link BinarySpec} implementations. However, it is generally better to use an
 * interface annotated with {@link com.tyron.builder.model.Managed} and not use an implementation class at all.
 */
@Incubating
public class BaseBinarySpec extends AbstractBuildableComponentSpec implements BinarySpecInternal {
    private static final ModelType<BinaryTasksCollection> BINARY_TASKS_COLLECTION = ModelType.of(BinaryTasksCollection.class);
    private static final ModelType<LanguageSourceSet> LANGUAGE_SOURCE_SET_MODELTYPE = ModelType.of(LanguageSourceSet.class);

    private static final ThreadLocal<BinaryInfo> NEXT_BINARY_INFO = new ThreadLocal<BinaryInfo>();
    private final DomainObjectSet<LanguageSourceSet> inputSourceSets;
    private final BinaryTasksCollection tasks;
    private final MutableModelNode componentNode;
    private final MutableModelNode sources;
    private final Class<? extends BinarySpec> publicType;
    private BinaryNamingScheme namingScheme;
    private boolean disabled;

    /**
     * Creates a {@link BaseBinarySpec}.
     *
     * @since 5.6
     */
    public static <T extends BaseBinarySpec> T create(Class<? extends BinarySpec> publicType, Class<T> implementationType,
                                                      ComponentSpecIdentifier componentId, MutableModelNode modelNode, @Nullable MutableModelNode componentNode,
                                                      Instantiator instantiator, NamedEntityInstantiator<Task> taskInstantiator,
                                                      CollectionCallbackActionDecorator collectionCallbackActionDecorator, DomainObjectCollectionFactory domainObjectCollectionFactory) {
        NEXT_BINARY_INFO.set(new BinaryInfo(componentId, publicType, modelNode, componentNode, taskInstantiator, instantiator, collectionCallbackActionDecorator, domainObjectCollectionFactory));
        try {
            try {
                return instantiator.newInstance(implementationType);
            } catch (ObjectInstantiationException e) {
                throw new ModelInstantiationException(String.format("Could not create binary of type %s", publicType.getSimpleName()), e.getCause());
            }
        } finally {
            NEXT_BINARY_INFO.set(null);
        }
    }

    public BaseBinarySpec() {
        this(NEXT_BINARY_INFO.get());
    }

    private BaseBinarySpec(BinaryInfo info) {
        super(validate(info).componentId, info.publicType);
        this.publicType = info.publicType;
        this.componentNode = info.componentNode;
        this.tasks = info.instantiator.newInstance(DefaultBinaryTasksCollection.class, this, info.taskInstantiator, info.collectionCallbackActionDecorator);
        this.inputSourceSets = info.domainObjectCollectionFactory.newDomainObjectSet(LanguageSourceSet.class);

        MutableModelNode modelNode = info.modelNode;
        sources = ModelMaps.addModelMapNode(modelNode, LANGUAGE_SOURCE_SET_MODELTYPE, "sources");
        ModelRegistration itemRegistration = ModelRegistrations.of(modelNode.getPath().child("tasks"))
            .action(ModelActionRole.Create, new Action<MutableModelNode>() {
                @Override
                public void execute(MutableModelNode modelNode) {
                    modelNode.setPrivateData(BINARY_TASKS_COLLECTION, tasks);
                }
            })
            .withProjection(new UnmanagedModelProjection<BinaryTasksCollection>(BINARY_TASKS_COLLECTION))
            .descriptor(modelNode.getDescriptor())
            .build();
        modelNode.addLink(itemRegistration);

        namingScheme = DefaultBinaryNamingScheme
            .component(parentComponentName())
            .withBinaryName(getName())
            .withBinaryType(getTypeName());
    }

    private static BinaryInfo validate(BinaryInfo info) {
        if (info == null) {
            throw new ModelInstantiationException("Direct instantiation of a BaseBinarySpec is not permitted. Use a @ComponentType rule instead.");
        }
        return info;
    }

    @Nullable
    private String parentComponentName() {
        ComponentSpec component = getComponent();
        return component != null ? component.getName() : null;
    }

    @Override
    public LibraryBinaryIdentifier getId() {
        // TODO: This can throw a NPE: will need an identifier for a variant without an owning component
        ComponentSpec component = getComponent();
        return new DefaultLibraryBinaryIdentifier(component.getProjectPath(), component.getName(), getName());
    }

    @Override
    public Class<? extends BinarySpec> getPublicType() {
        return publicType;
    }

    @Override
    @Nullable
    public ComponentSpec getComponent() {
        return getComponentAs(ComponentSpec.class);
    }

    @Nullable
    protected <T extends ComponentSpec> T getComponentAs(Class<T> componentType) {
        if (componentNode == null) {
            return null;
        }
        ModelType<T> modelType = ModelType.of(componentType);
        return componentNode.canBeViewedAs(modelType)
            ? componentNode.asImmutable(modelType, componentNode.getDescriptor()).getInstance()
            : null;
    }

    @Override
    public String getProjectScopedName() {
        return getIdentifier().getProjectScopedName();
    }

    @Override
    public void setBuildable(boolean buildable) {
        this.disabled = !buildable;
    }

    @Override
    public final boolean isBuildable() {
        return getBuildAbility().isBuildable();
    }

    @Override
    public DomainObjectSet<LanguageSourceSet> getInputs() {
        return inputSourceSets;
    }

    @Override
    public ModelMap<LanguageSourceSet> getSources() {
        return ModelMaps.toView(sources, LANGUAGE_SOURCE_SET_MODELTYPE);
    }

    @Override
    public BinaryTasksCollection getTasks() {
        return tasks;
    }

    @Override
    public boolean isLegacyBinary() {
        return false;
    }

    @Override
    public BinaryNamingScheme getNamingScheme() {
        return namingScheme;
    }

    @Override
    public void setNamingScheme(BinaryNamingScheme namingScheme) {
        this.namingScheme = namingScheme;
    }

    @Override
    public boolean hasCodependentSources() {
        return false;
    }

    private static class BinaryInfo {
        private final Class<? extends BinarySpec> publicType;
        private final MutableModelNode modelNode;
        private final MutableModelNode componentNode;
        private final NamedEntityInstantiator<Task> taskInstantiator;
        private final Instantiator instantiator;
        private final ComponentSpecIdentifier componentId;
        private final CollectionCallbackActionDecorator collectionCallbackActionDecorator;
        private final DomainObjectCollectionFactory domainObjectCollectionFactory;

        private BinaryInfo(ComponentSpecIdentifier componentId, Class<? extends BinarySpec> publicType, MutableModelNode modelNode, MutableModelNode componentNode, NamedEntityInstantiator<Task> taskInstantiator, Instantiator instantiator, CollectionCallbackActionDecorator collectionCallbackActionDecorator, DomainObjectCollectionFactory domainObjectCollectionFactory) {
            this.componentId = componentId;
            this.publicType = publicType;
            this.modelNode = modelNode;
            this.componentNode = componentNode;
            this.taskInstantiator = taskInstantiator;
            this.instantiator = instantiator;
            this.collectionCallbackActionDecorator = collectionCallbackActionDecorator;
            this.domainObjectCollectionFactory = domainObjectCollectionFactory;
        }
    }

    @Override
    public final BinaryBuildAbility getBuildAbility() {
        if (disabled) {
            return new FixedBuildAbility(false);
        }
        return getBinaryBuildAbility();
    }

    protected BinaryBuildAbility getBinaryBuildAbility() {
        // Default behavior is to always be buildable.  Binary implementations should define what
        // criteria make them buildable or not.
        return new FixedBuildAbility(true);
    }

    public static void replaceSingleDirectory(Set<File> dirs, File dir) {
        switch (dirs.size()) {
            case 0:
                dirs.add(dir);
                break;
            case 1:
                dirs.clear();
                dirs.add(dir);
                break;
            default:
                throw new IllegalStateException("Can't replace multiple directories.");
        }
    }

}
