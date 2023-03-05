package org.gradle.workers.internal;

import org.gradle.api.specs.Spec;
import org.gradle.internal.Cast;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolated.IsolationScheme;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceLookup;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

import java.util.Collection;

public class DefaultWorkerServer implements Worker {
    private final ServiceRegistry internalServices;
    private final InstantiatorFactory instantiatorFactory;
    private final IsolationScheme<WorkAction<?>, WorkParameters> isolationScheme;
    private final Collection<? extends Class<?>> additionalWhitelistedServices;

    public DefaultWorkerServer(ServiceRegistry internalServices, InstantiatorFactory instantiatorFactory, IsolationScheme<WorkAction<?>, WorkParameters> isolationScheme, Collection<? extends Class<?>> additionalWhitelistedServices) {
        this.internalServices = internalServices;
        this.instantiatorFactory = instantiatorFactory;
        this.isolationScheme = isolationScheme;
        this.additionalWhitelistedServices = additionalWhitelistedServices;
    }

    @Override
    public DefaultWorkResult execute(SimpleActionExecutionSpec<?> spec) {
        try {
            Class<? extends WorkAction<?>> implementationClass = Cast.uncheckedCast(spec.getImplementationClass());
            // Exceptions to services available for injection
            Spec<Class<?>> whiteListPolicy;
            if (spec.isInternalServicesRequired()) {
                whiteListPolicy = element -> true;
            } else {
                whiteListPolicy = element -> false;
            }
            ServiceLookup instantiationServices = isolationScheme.servicesForImplementation(spec.getParameters(), internalServices, additionalWhitelistedServices, whiteListPolicy);
            Instantiator instantiator = instantiatorFactory.inject(instantiationServices);
            WorkAction<?> execution;
            if (ProvidesWorkResult.class.isAssignableFrom(implementationClass)) {
                execution = instantiator.newInstance(implementationClass, spec.getParameters(), instantiator);
            } else {
                execution = instantiator.newInstance(implementationClass);
            }
            execution.execute();
            if (execution instanceof ProvidesWorkResult) {
                return ((ProvidesWorkResult) execution).getWorkResult();
            } else {
                return DefaultWorkResult.SUCCESS;
            }
        } catch (Throwable t) {
            return new DefaultWorkResult(true, t);
        }
    }

    @Override
    public String toString() {
        return "DefaultWorkerServer{}";
    }
}
