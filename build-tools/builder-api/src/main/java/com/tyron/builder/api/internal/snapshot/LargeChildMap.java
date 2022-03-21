package com.tyron.builder.api.internal.snapshot;

import java.util.List;

import java.util.List;

public class LargeChildMap<T> extends AbstractListChildMap<T> {

    public LargeChildMap(List<Entry<T>> children) {
        super(children);
    }

    @Override
    public <R> R withNode(VfsRelativePath targetPath, CaseSensitivity caseSensitivity, NodeHandler<T, R> handler) {
        int childIndexWithCommonPrefix = findChildIndexWithCommonPrefix(targetPath, caseSensitivity);
        if (childIndexWithCommonPrefix >= 0) {
            Entry<T> entry = entries.get(childIndexWithCommonPrefix);
            return entry.withNode(targetPath, caseSensitivity, handler);
        }
        return handler.handleUnrelatedToAnyChild();
    }
}