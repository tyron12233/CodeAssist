package com.tyron.builder.workers.internal;

import com.google.common.base.Objects;
import com.tyron.builder.internal.classloader.ClassLoaderSpec;

public class HierarchicalClassLoaderStructure implements ClassLoaderStructure {
    private final ClassLoaderSpec self;
    private final HierarchicalClassLoaderStructure parent;

    public HierarchicalClassLoaderStructure(ClassLoaderSpec self) {
        this(self, null);
    }

    public HierarchicalClassLoaderStructure(ClassLoaderSpec self, HierarchicalClassLoaderStructure parent) {
        this.self = self;
        this.parent = parent;
    }

    public HierarchicalClassLoaderStructure withChild(ClassLoaderSpec spec) {
        HierarchicalClassLoaderStructure childNode = new HierarchicalClassLoaderStructure(spec, this);
        return childNode;
    }

    @Override
    public ClassLoaderSpec getSpec() {
        return self;
    }

    @Override
    public HierarchicalClassLoaderStructure getParent() {
        return parent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HierarchicalClassLoaderStructure that = (HierarchicalClassLoaderStructure) o;
        return Objects.equal(self, that.self) &&
                Objects.equal(parent, that.parent);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(self, parent);
    }

    @Override
    public String toString() {
        return "HierarchicalClassLoaderStructure{" +
                "self=" + self +
                ", parent=" + parent +
                '}';
    }
}
