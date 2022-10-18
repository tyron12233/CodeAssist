package org.gradle.api.internal;

import groovy.lang.Closure;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.internal.metaobject.ConfigureDelegate;
import org.gradle.internal.metaobject.DynamicInvokeResult;

public class NamedDomainObjectContainerConfigureDelegate extends ConfigureDelegate {
    private final NamedDomainObjectContainer _container;

    public NamedDomainObjectContainerConfigureDelegate(Closure<?> configureClosure, NamedDomainObjectContainer<?> container) {
        super(configureClosure, container);
        _container = container;
    }

    @Override
    protected DynamicInvokeResult _configure(String name) {
        return DynamicInvokeResult.found(_container.create(name));
    }

    @Override
    protected DynamicInvokeResult _configure(String name, Object[] params) {
        if (params.length == 1 && params[0] instanceof Closure) {
            return DynamicInvokeResult.found(_container.create(name, (Closure) params[0]));
        }
        return DynamicInvokeResult.notFound();
    }
}
