package com.tyron.builder.workers.internal;

import com.tyron.builder.api.internal.classloading.GroovySystemLoader;
import com.tyron.builder.api.internal.classloading.GroovySystemLoaderFactory;
import com.tyron.builder.initialization.LegacyTypesSupport;
import com.tyron.builder.initialization.MixInLegacyTypesClassLoader;
import com.tyron.builder.internal.classloader.ClassLoaderSpec;
import com.tyron.builder.internal.classloader.FilteringClassLoader;
import com.tyron.builder.internal.classloader.VisitableURLClassLoader;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.service.ServiceRegistry;

public class IsolatedClassloaderWorker extends AbstractClassLoaderWorker {
    private final GroovySystemLoaderFactory groovySystemLoaderFactory = new GroovySystemLoaderFactory();
    private ClassLoader workerClassLoader;
    private boolean reuseClassloader;

    public IsolatedClassloaderWorker(ClassLoader workerClassLoader, ServiceRegistry workServices, ActionExecutionSpecFactory actionExecutionSpecFactory, InstantiatorFactory instantiatorFactory) {
        super(workServices, actionExecutionSpecFactory, instantiatorFactory);
        this.workerClassLoader = workerClassLoader;
    }

    public IsolatedClassloaderWorker(ClassLoader workerClassLoader, ServiceRegistry workServices, ActionExecutionSpecFactory actionExecutionSpecFactory, InstantiatorFactory instantiatorFactory, boolean reuseClassloader) {
        this(workerClassLoader, workServices, actionExecutionSpecFactory, instantiatorFactory);
        this.reuseClassloader = reuseClassloader;
    }

    @Override
    public DefaultWorkResult run(TransportableActionExecutionSpec spec) {
        GroovySystemLoader workerClasspathGroovy = groovySystemLoaderFactory.forClassLoader(workerClassLoader);
        try {
            return executeInClassLoader(spec, workerClassLoader);
        } finally {
            workerClasspathGroovy.shutdown();
            // TODO: we should just cache these classloaders and eject/stop them when they are no longer in use
            if (!reuseClassloader) {
                CompositeStoppable.stoppable(workerClassLoader).stop();
                this.workerClassLoader = null;
            }
        }
    }

    static ClassLoader createIsolatedWorkerClassloader(ClassLoaderStructure classLoaderStructure, ClassLoader workerInfrastructureClassloader, LegacyTypesSupport legacyTypesSupport) {
        return createWorkerClassLoaderWithStructure(workerInfrastructureClassloader, classLoaderStructure, legacyTypesSupport);
    }

    private static ClassLoader createWorkerClassLoaderWithStructure(ClassLoader workerInfrastructureClassloader, ClassLoaderStructure classLoaderStructure, LegacyTypesSupport legacyTypesSupport) {
        if (classLoaderStructure.getParent() == null) {
            // This is the highest parent in the hierarchy
            return createClassLoaderFromSpec(workerInfrastructureClassloader, classLoaderStructure.getSpec(), legacyTypesSupport);
        } else {
            // Climb up the hierarchy looking for the highest parent
            ClassLoader parent = createWorkerClassLoaderWithStructure(workerInfrastructureClassloader, classLoaderStructure.getParent(), legacyTypesSupport);
            return createClassLoaderFromSpec(parent, classLoaderStructure.getSpec(), legacyTypesSupport);
        }
    }

    private static ClassLoader createClassLoaderFromSpec(ClassLoader parent, ClassLoaderSpec spec, LegacyTypesSupport legacyTypesSupport) {
        if (spec instanceof MixInLegacyTypesClassLoader.Spec) {
            MixInLegacyTypesClassLoader.Spec mixinSpec = (MixInLegacyTypesClassLoader.Spec) spec;
            if (mixinSpec.getClasspath().isEmpty()) {
                return parent;
            }
            return new MixInLegacyTypesClassLoader(parent, mixinSpec.getClasspath(), legacyTypesSupport);
        } else if (spec instanceof VisitableURLClassLoader.Spec) {
            VisitableURLClassLoader.Spec visitableSpec = (VisitableURLClassLoader.Spec) spec;
            if (visitableSpec.getClasspath().isEmpty()) {
                return parent;
            }
            return new VisitableURLClassLoader(visitableSpec.getName(), parent, visitableSpec.getClasspath());
        } else if (spec instanceof FilteringClassLoader.Spec) {
            FilteringClassLoader.Spec filteringSpec = (FilteringClassLoader.Spec) spec;
            if (filteringSpec.isEmpty()) {
                return parent;
            }
            return new FilteringClassLoader(parent, filteringSpec);
        } else {
            throw new IllegalArgumentException("Can't handle spec of type " + spec.getClass().getName());
        }
    }
}
