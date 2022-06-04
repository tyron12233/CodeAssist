package com.tyron.builder.api.internal.tasks;

import groovy.lang.Closure;
import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.ConventionMapping;
import com.tyron.builder.api.internal.IConventionAware;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.internal.extensibility.ConventionAwareHelper;
import com.tyron.builder.work.DisableCachingByDefault;

import java.util.concurrent.Callable;

@DisableCachingByDefault(because = "Abstract super-class, not to be instantiated directly")
public abstract class ConventionTask extends DefaultTask implements IConventionAware {
    private ConventionMapping conventionMapping;

    public Task conventionMapping(String property, Callable<?> mapping) {
        getConventionMapping().map(property, mapping);
        return this;
    }

    public Task conventionMapping(String property, Closure mapping) {
        getConventionMapping().map(property, mapping);
        return this;
    }

    @Override
    @Internal
    @SuppressWarnings("deprecation")
    public ConventionMapping getConventionMapping() {
        if (conventionMapping == null) {
            conventionMapping = new ConventionAwareHelper(this, getConvention());
        }
        return conventionMapping;
    }
}
