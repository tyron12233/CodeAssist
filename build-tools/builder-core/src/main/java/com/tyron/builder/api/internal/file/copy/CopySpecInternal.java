package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.file.CopySpec;
import com.tyron.builder.api.file.FileCopyDetails;

import javax.annotation.Nullable;

public interface CopySpecInternal extends CopySpec {

    Iterable<CopySpecInternal> getChildren();

    CopySpecInternal addChild();

    CopySpecInternal addChildBeforeSpec(CopySpecInternal childSpec);

    CopySpecInternal addFirst();

    void walk(Action<? super CopySpecResolver> action);

    CopySpecResolver buildRootResolver();

    CopySpecResolver buildResolverRelativeToParent(CopySpecResolver parent);

    void addChildSpecListener(CopySpecListener listener);

    void visit(CopySpecAddress parentPath, CopySpecVisitor visitor);

    /**
     * Returns whether the spec, or any of its children have custom copy actions.
     */
    boolean hasCustomActions();

    void appendCachingSafeCopyAction(Action<? super FileCopyDetails> action);

    /**
     * Listener triggered when a spec is added to the hierarchy.
     */
    interface CopySpecListener {
        void childSpecAdded(CopySpecAddress path, CopySpecInternal spec);
    }

    /**
     * A visitor to traverse the spec hierarchy.
     */
    interface CopySpecVisitor {
        void visit(CopySpecAddress address, CopySpecInternal spec);
    }

    /**
     * The address of a spec relative to its parent.
     */
    interface CopySpecAddress {
        @Nullable
        CopySpecAddress getParent();

        CopySpecInternal getSpec();

        int getAdditionIndex();

        CopySpecAddress append(CopySpecInternal spec, int additionIndex);

        CopySpecAddress append(CopySpecAddress relativeAddress);

        CopySpecResolver unroll(StringBuilder path);
    }
}
