package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.file.FileCollectionInternal;
import com.tyron.builder.api.internal.file.UnionFileCollection;
import com.tyron.builder.internal.graph.CachingDirectedGraphWalker;
import com.tyron.builder.internal.graph.DirectedGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class CachingDependencyResolveContext implements DependencyResolveContext {
    private final List<Object> queue = new ArrayList<Object>();
    private final CachingDirectedGraphWalker<Object, FileCollectionInternal> walker = new CachingDirectedGraphWalker<Object, FileCollectionInternal>(new DependencyGraph());
    private final boolean transitive;
    private final Map<String, String> attributes;

    public CachingDependencyResolveContext(boolean transitive, Map<String, String> attributes) {
        this.transitive = transitive;
        this.attributes = attributes;
    }

    @Override
    public boolean isTransitive() {
        return transitive;
    }

    @Override
    public Map<String, String> getAttributes() {
        return attributes;
    }

    public FileCollection resolve() {
        try {
            walker.add(queue);
            return new UnionFileCollection(walker.findValues());
        } finally {
            queue.clear();
        }
    }

    @Override
    public void add(Object dependency) {
        queue.add(dependency);
    }

    private class DependencyGraph implements DirectedGraph<Object, FileCollectionInternal> {
        @Override
        public void getNodeValues(Object node, Collection<? super FileCollectionInternal> values, Collection<? super Object> connectedNodes) {
            if (node instanceof FileCollectionInternal) {
                FileCollectionInternal fileCollection = (FileCollectionInternal) node;
                values.add(fileCollection);
            } else if (node instanceof ResolvableDependency) {
                ResolvableDependency resolvableDependency = (ResolvableDependency) node;
                queue.clear();
                resolvableDependency.resolve(CachingDependencyResolveContext.this);
                connectedNodes.addAll(queue);
                queue.clear();
            } else {
                throw new IllegalArgumentException(String.format("Cannot resolve object of unknown type %s.", node.getClass().getSimpleName()));
            }
        }
    }
}
