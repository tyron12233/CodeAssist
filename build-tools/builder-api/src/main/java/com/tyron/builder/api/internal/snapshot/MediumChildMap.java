package com.tyron.builder.api.internal.snapshot;

import java.util.List;

import java.util.List;
import java.util.Optional;

public class MediumChildMap<T> extends AbstractListChildMap<T> {
    protected MediumChildMap(List<Entry<T>> children) {
        super(children);
    }

    @Override
    public <RESULT> RESULT withNode(VfsRelativePath targetPath, CaseSensitivity caseSensitivity, NodeHandler<T, RESULT> handler) {
        for (Entry<T> entry : entries) {
            Optional<RESULT> ancestorDescendantOrExactMatchResult = entry.handleAncestorDescendantOrExactMatch(targetPath, caseSensitivity, handler);
            if (ancestorDescendantOrExactMatchResult.isPresent()) {
                return ancestorDescendantOrExactMatchResult.get();
            }
        }
        return handler.handleUnrelatedToAnyChild();
    }
}