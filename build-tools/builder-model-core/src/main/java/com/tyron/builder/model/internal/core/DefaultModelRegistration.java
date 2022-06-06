package com.tyron.builder.model.internal.core;

import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;
import javax.annotation.concurrent.ThreadSafe;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

@ThreadSafe
public class DefaultModelRegistration implements ModelRegistration {
    private final ModelPath path;
    private final ModelRuleDescriptor descriptor;
    private final boolean hidden;
    private final Multimap<ModelActionRole, ? extends ModelAction> actions;

    public DefaultModelRegistration(ModelPath path, ModelRuleDescriptor descriptor,
                                    boolean hidden, Multimap<ModelActionRole, ? extends ModelAction> actions) {
        this.path = Preconditions.checkNotNull(path, "path");
        this.descriptor = Preconditions.checkNotNull(descriptor, "descriptor");
        this.hidden = hidden;
        this.actions = Preconditions.checkNotNull(actions, "actions");
    }

    @Override
    public ModelPath getPath() {
        return path;
    }

    @Override
    public Multimap<ModelActionRole, ? extends ModelAction> getActions() {
        return actions;
    }

    @Override
    public boolean isHidden() {
        return hidden;
    }

    @Override
    public ModelRuleDescriptor getDescriptor() {
        return descriptor;
    }
}
