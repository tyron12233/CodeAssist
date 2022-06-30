package com.tyron.builder.tooling.internal.provider.serialization;

import static com.tyron.builder.internal.classpath.CachedClasspathTransformer.StandardTransform.None;

import java.net.URL;
import java.util.Collection;
import java.util.List;

import com.tyron.builder.internal.classloader.ClassLoaderSpec;
import com.tyron.builder.internal.classloader.VisitableURLClassLoader;
import com.tyron.builder.internal.classpath.CachedClasspathTransformer;

public class DaemonSidePayloadClassLoaderFactory implements PayloadClassLoaderFactory {
    private final PayloadClassLoaderFactory delegate;
    private final CachedClasspathTransformer cachedClasspathTransformer;

    public DaemonSidePayloadClassLoaderFactory(PayloadClassLoaderFactory delegate, CachedClasspathTransformer cachedClasspathTransformer) {
        this.delegate = delegate;
        this.cachedClasspathTransformer = cachedClasspathTransformer;
    }

    @Override
    public ClassLoader getClassLoaderFor(ClassLoaderSpec spec, List<? extends ClassLoader> parents) {
        if (spec instanceof ClientOwnedClassLoaderSpec) {
            ClientOwnedClassLoaderSpec clientSpec = (ClientOwnedClassLoaderSpec) spec;
            return createClassLoaderForClassPath("client-owned-daemon-payload-loader", parents, clientSpec.getClasspath());
        }
        if (spec instanceof VisitableURLClassLoader.Spec) {
            VisitableURLClassLoader.Spec urlSpec = (VisitableURLClassLoader.Spec) spec;
            return createClassLoaderForClassPath(urlSpec.getName() + "-daemon-payload-loader", parents, urlSpec.getClasspath());
        }
        return delegate.getClassLoaderFor(spec, parents);
    }

    private ClassLoader createClassLoaderForClassPath(String name, List<? extends ClassLoader> parents, List<URL> classpath) {
        if (parents.size() != 1) {
            throw new IllegalStateException("Expected exactly one parent ClassLoader");
        }

        // convert the file urls to cached jar files
        Collection<URL> cachedClassPathUrls = cachedClasspathTransformer.transform(classpath, None);

        return new VisitableURLClassLoader(name, parents.get(0), cachedClassPathUrls);
    }
}
