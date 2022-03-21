package com.tyron.builder.api.execution.plan;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.collect.PersistentList;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.snapshot.ChildMap;
import com.tyron.builder.api.internal.snapshot.ChildMapFactory;
import com.tyron.builder.api.internal.snapshot.EmptyChildMap;
import com.tyron.builder.api.internal.snapshot.VfsRelativePath;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A hierarchy of relative paths with attached values.
 *
 * This is an immutable data structure.
 */
public final class ValuedVfsHierarchy<T> {
    private final PersistentList<T> values;

    private final ChildMap<ValuedVfsHierarchy<T>> children;
    private final CaseSensitivity caseSensitivity;

    public static <T> ValuedVfsHierarchy<T> emptyHierarchy(CaseSensitivity caseSensitivity) {
        return new ValuedVfsHierarchy<>(PersistentList.of(), EmptyChildMap.getInstance(),
                                        caseSensitivity);
    }

    private ValuedVfsHierarchy(PersistentList<T> values,
                               ChildMap<ValuedVfsHierarchy<T>> children,
                               CaseSensitivity caseSensitivity) {
        this.values = values;
        this.children = children;
        this.caseSensitivity = caseSensitivity;
    }

    public boolean isEmpty() {
        return children.isEmpty() && values.isEmpty();
    }

    /**
     * Returns an empty {@link ValuedVfsHierarchy} with the same case sensitivity.
     */
    public ValuedVfsHierarchy<T> empty() {
        return emptyHierarchy(caseSensitivity);
    }

    /**
     * Visits the values which are attached to ancestors and children of the given location.
     */
    public void visitValues(String location, ValueVisitor<T> visitor) {
        VfsRelativePath relativePath = VfsRelativePath.of(location);
        if (relativePath.isEmpty()) {
            visitAllValues(visitor);
        } else {
            visitValuesRelatedTo(relativePath, visitor);
        }
    }

    /**
     * Visits the values which are attached to ancestors and children of the given location.
     * <p>
     * The location must not be empty.
     */
    private void visitValuesRelatedTo(VfsRelativePath location, ValueVisitor<T> visitor) {
        values.forEach(value -> visitor.visitAncestor(value, location));
        children.withNode(location, caseSensitivity, new ChildMap.NodeHandler<ValuedVfsHierarchy<T>, String>() {
            @Override
            public String handleAsDescendantOfChild(VfsRelativePath pathInChild, ValuedVfsHierarchy<T> child) {
                child.visitValuesRelatedTo(pathInChild, visitor);
                return "";
            }

            @Override
            public String handleAsAncestorOfChild(String childPathFromAncestor, ValuedVfsHierarchy<T> child) {
                visitor.visitChildren(child.getValues(), () -> location.pathToChild(childPathFromAncestor));
                child.visitAllChildren((nodes, relativePath) -> visitor
                        .visitChildren(nodes, () -> joinRelativePaths(location.pathToChild(childPathFromAncestor),
                                                                      relativePath.get())));
                return "";
            }

            @Override
            public String handleExactMatchWithChild(ValuedVfsHierarchy<T> child) {
                child.visitAllValues(visitor);
                return "";
            }

            @Override
            public String handleUnrelatedToAnyChild() {
                return "";
            }
        });
    }

    /**
     * Visits all values relative to the root.
     */
    private void visitAllValues(ValueVisitor<T> valueVisitor) {
        getValues().forEach(valueVisitor::visitExact);
        visitAllChildren(valueVisitor::visitChildren);
    }

    public interface ValueVisitor<T> {
        /**
         * The visited value is attached to the given location.
         */
        void visitExact(T value);

        /**
         * The visited value is an ancestor of the visited location
         */
        void visitAncestor(T value, VfsRelativePath pathToVisitedLocation);

        /**
         * The visited value is a child of the visited location.
         *
         * @param relativePathSupplier provides the relative path from the visited location to the path with the attached values.
         */
        void visitChildren(PersistentList<T> values, Supplier<String> relativePathSupplier);
    }

    /**
     * Returns a new {@link ValuedVfsHierarchy} with the value attached to the location.
     */
    public ValuedVfsHierarchy<T> recordValue(VfsRelativePath location, T value) {
        if (location.isEmpty()) {
            return new ValuedVfsHierarchy<>(values.plus(value), children, caseSensitivity);
        }
        ChildMap<ValuedVfsHierarchy<T>> newChildren = children.store(location, caseSensitivity, new ChildMap.StoreHandler<ValuedVfsHierarchy<T>>() {
            @Override
            public ValuedVfsHierarchy<T> handleAsDescendantOfChild(VfsRelativePath pathInChild,
                                                                   ValuedVfsHierarchy<T> child) {
                return child.recordValue(pathInChild, value);
            }

            @Override
            public ValuedVfsHierarchy<T> handleAsAncestorOfChild(String childPath,
                                                                 ValuedVfsHierarchy<T> child) {
                ChildMap<ValuedVfsHierarchy<T>> singletonChild = ChildMapFactory.childMapFromSorted(
                        ImmutableList.of(new ChildMap.Entry<>(location.pathToChild(childPath), child)));
                return new ValuedVfsHierarchy<>(PersistentList.of(value), singletonChild,
                                                caseSensitivity);
            }

            @Override
            public ValuedVfsHierarchy<T> mergeWithExisting(ValuedVfsHierarchy<T> child) {
                return new ValuedVfsHierarchy<>(child.getValues().plus(value), child.getChildren(),
                                                caseSensitivity);
            }

            @Override
            public ValuedVfsHierarchy<T> createChild() {
                return new ValuedVfsHierarchy<>(PersistentList.of(value), EmptyChildMap.getInstance(),
                                                caseSensitivity);
            }

            @Override
            public ValuedVfsHierarchy<T> createNodeFromChildren(ChildMap<ValuedVfsHierarchy<T>> children) {
                return new ValuedVfsHierarchy<>(PersistentList.of(), children, caseSensitivity);
            }
        });
        return new ValuedVfsHierarchy<>(values, newChildren, caseSensitivity);
    }

    private PersistentList<T> getValues() {
        return values;
    }

    private void visitAllChildren(BiConsumer<PersistentList<T>, Supplier<String>> childConsumer) {
        children.stream().forEach(entry -> {
            ValuedVfsHierarchy<T> child = entry.getValue();
            childConsumer.accept(child.getValues(), entry::getPath);
            child.visitAllChildren((grandChildren, relativePath) -> childConsumer
                    .accept(grandChildren, () -> joinRelativePaths(entry.getPath(),
                                                                   relativePath.get())));
        });
    }

    private ChildMap<ValuedVfsHierarchy<T>> getChildren() {
        return children;
    }

    private static String joinRelativePaths(String first, String second) {
        return first + "/" + second;
    }
}
