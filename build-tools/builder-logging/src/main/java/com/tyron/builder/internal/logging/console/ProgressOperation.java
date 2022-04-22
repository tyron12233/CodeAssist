package com.tyron.builder.internal.logging.console;

import com.tyron.builder.util.GUtil;
import com.tyron.builder.internal.operations.OperationIdentifier;

import java.util.HashSet;
import java.util.Set;

public class ProgressOperation {

    private String status;
    private final String category;
    private final OperationIdentifier operationId;
    private final ProgressOperation parent;
    private Set<ProgressOperation> children;

    public ProgressOperation(String status, String category, OperationIdentifier operationId, ProgressOperation parent) {
        this.status = status;
        this.category = category;
        this.operationId = operationId;
        this.parent = parent;
    }

    @Override
    public String toString() {
        return String.format("id=%s, category=%s, status=%s", operationId, category, status);
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        if (GUtil.isTrue(status)) {
            return status;
        }
        return null;
    }

    public String getCategory() {
        return category;
    }

    public OperationIdentifier getOperationId() {
        return operationId;
    }

    public ProgressOperation getParent() {
        return parent;
    }

    public boolean addChild(ProgressOperation operation) {
        if (children == null) {
            children = new HashSet<ProgressOperation>();
        }
        return children.add(operation);
    }

    public boolean removeChild(ProgressOperation operation) {
        if (children == null) {
            throw new IllegalStateException(String.format("Cannot remove child operation [%s] from operation with no children [%s]", operation, this));
        }
        return children.remove(operation);
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }
}

