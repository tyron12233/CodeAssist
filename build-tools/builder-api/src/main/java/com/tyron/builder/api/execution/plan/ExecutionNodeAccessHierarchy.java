package com.tyron.builder.api.execution.plan;


import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.internal.collect.PersistentList;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.snapshot.VfsRelativePath;

import java.io.File;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class ExecutionNodeAccessHierarchy {
    private volatile ValuedVfsHierarchy<NodeAccess> root;
    private final SingleFileTreeElementMatcher matcher;

    public ExecutionNodeAccessHierarchy(CaseSensitivity caseSensitivity, Stat stat) {
        this.root = ValuedVfsHierarchy.emptyHierarchy(caseSensitivity);
        this.matcher = new SingleFileTreeElementMatcher(stat);
    }

    /**
     * Returns all nodes which access the location.
     *
     * That includes node which access ancestors or children of the location.
     */
    public ImmutableSet<Node> getNodesAccessing(String location) {
        return visitValues(location, new AbstractNodeAccessVisitor() {
            @Override
            public void visitChildren(PersistentList<NodeAccess> values, Supplier<String> relativePathSupplier) {
                values.forEach(this::addNode);
            }
        });
    }

    /**
     * Returns all nodes which access the location, taking into account the filter.
     *
     * That includes nodes which access ancestors or children of the location.
     * Nodes accessing children of the location are only included if the children match the filter.
     */
    public ImmutableSet<Node> getNodesAccessing(String location, Predicate<FileTreeElement> filter) {
        return visitValues(location, new AbstractNodeAccessVisitor() {
            @Override
            public void visitChildren(PersistentList<NodeAccess> values, Supplier<String> relativePathSupplier) {
                String relativePathFromLocation = relativePathSupplier.get();
                if (matcher.elementWithRelativePathMatches(filter, new File(location, relativePathFromLocation), relativePathFromLocation)) {
                    values.forEach(this::addNode);
                }
            }
        });
    }

    /**
     * Records that a node accesses the given locations.
     */
    public synchronized void recordNodeAccessingLocations(Node node, Iterable<String> accessedLocations) {
        for (String location : accessedLocations) {
            VfsRelativePath relativePath = VfsRelativePath.of(location);
            root = root.recordValue(relativePath, new DefaultNodeAccess(node));
        }
    }

    /**
     * Records that a node accesses the fileTreeRoot with a filter.
     *
     * The node only accesses children of the fileTreeRoot if they match the filter.
     * This is taken into account when using {@link #getNodesAccessing(String)} and {@link #getNodesAccessing(String, Spec)}.
     */
    public synchronized void recordNodeAccessingFileTree(Node node, String fileTreeRoot, Predicate<FileTreeElement> filter) {
        VfsRelativePath relativePath = VfsRelativePath.of(fileTreeRoot);
        root = root.recordValue(relativePath, new FilteredNodeAccess(node, filter));
    }

    /**
     * Removes all recorded nodes.
     */
    public synchronized void clear() {
        root = root.empty();
    }

    private ImmutableSet<Node> visitValues(String location, AbstractNodeAccessVisitor visitor) {
        root.visitValues(location, visitor);
        return visitor.getResult();
    }

    private abstract static class AbstractNodeAccessVisitor implements ValuedVfsHierarchy.ValueVisitor<NodeAccess> {

        private final ImmutableSet.Builder<Node> builder = ImmutableSet.builder();

        public void addNode(NodeAccess value) {
            builder.add(value.getNode());
        }

        @Override
        public void visitExact(NodeAccess value) {
            addNode(value);
        }

        @Override
        public void visitAncestor(NodeAccess value, VfsRelativePath pathToVisitedLocation) {
            if (value.accessesChild(pathToVisitedLocation)) {
                addNode(value);
            }
        }

        public ImmutableSet<Node> getResult() {
            return builder.build();
        }
    }

    private interface NodeAccess {
        Node getNode();
        boolean accessesChild(VfsRelativePath childPath);
    }

    private static class DefaultNodeAccess implements NodeAccess {

        private final Node node;

        public DefaultNodeAccess(Node node) {
            this.node = node;
        }

        @Override
        public Node getNode() {
            return node;
        }

        @Override
        public boolean accessesChild(VfsRelativePath childPath) {
            return true;
        }
    }

    private class FilteredNodeAccess implements NodeAccess {
        private final Node node;
        private final Predicate<FileTreeElement> spec;

        public FilteredNodeAccess(Node node, Predicate<FileTreeElement> spec) {
            this.node = node;
            this.spec = spec;
        }

        @Override
        public Node getNode() {
            return node;
        }

        @Override
        public boolean accessesChild(VfsRelativePath childPath) {
            return matcher.elementWithRelativePathMatches(spec, new File(childPath.getAbsolutePath()), childPath.getAsString());
        }
    }
}