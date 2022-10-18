package org.gradle.api.internal;

import groovy.lang.Closure;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.tasks.Internal;
import org.gradle.internal.extensibility.ConventionAwareHelper;
import org.gradle.work.DisableCachingByDefault;

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
