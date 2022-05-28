package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

import java.util.Arrays;
import java.util.List;

public class AddProjectionsAction<T> extends AbstractModelAction<T> {
    private final Iterable<ModelProjection> projections;

    private AddProjectionsAction(ModelReference<T> subject, ModelRuleDescriptor descriptor, Iterable<ModelProjection> projections) {
        super(subject, descriptor);
        this.projections = projections;
    }

    public static <T> AddProjectionsAction<T> of(ModelReference<T> subject, ModelRuleDescriptor descriptor, ModelProjection... projections) {
        return of(subject, descriptor, Arrays.asList(projections));
    }

    public static <T> AddProjectionsAction<T> of(ModelReference<T> subject, ModelRuleDescriptor descriptor, Iterable<ModelProjection> projections) {
        return new AddProjectionsAction<T>(subject, descriptor, projections);
    }

    @Override
    public void execute(MutableModelNode modelNode, List<ModelView<?>> inputs) {
        for (ModelProjection projection : projections) {
            modelNode.addProjection(projection);
        }
    }
}
