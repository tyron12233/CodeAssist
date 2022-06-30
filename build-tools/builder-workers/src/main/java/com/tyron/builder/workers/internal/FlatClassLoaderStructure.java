package com.tyron.builder.workers.internal;

import com.google.common.base.Objects;
import com.tyron.builder.internal.classloader.VisitableURLClassLoader;

public class FlatClassLoaderStructure implements ClassLoaderStructure {
    private final VisitableURLClassLoader.Spec spec;

    public FlatClassLoaderStructure(VisitableURLClassLoader.Spec spec) {
        this.spec = spec;
    }

    @Override
    public VisitableURLClassLoader.Spec getSpec() {
        return spec;
    }

    @Override
    public ClassLoaderStructure getParent() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FlatClassLoaderStructure that = (FlatClassLoaderStructure) o;
        return Objects.equal(spec, that.spec);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(spec);
    }
}
