package com.tyron.builder.model.dsl.internal.transform;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.List;

public class InputReferences {
    private final List<InputReference> ownReferences = Lists.newArrayList();
    private final List<InputReference> nestedReferences = Lists.newArrayList();

    public List<InputReference> getOwnReferences() {
        return ownReferences;
    }

    public void ownReference(String path, int lineNumber) {
        ownReferences.add(new InputReference(path, lineNumber));
    }

    public boolean isEmpty() {
        return ownReferences.isEmpty() && nestedReferences.isEmpty();
    }

    public List<InputReference> getNestedReferences() {
        return nestedReferences;
    }

    public void addNestedReferences(InputReferences inputReferences) {
        nestedReferences.addAll(inputReferences.getOwnReferences());
        nestedReferences.addAll(inputReferences.getNestedReferences());
    }

    /**
     * Used from generated code, see {@link RuleVisitor#visitGeneratedClosure(org.codehaus.groovy.ast.ClassNode)}
     */
    @SuppressWarnings("unused")
    public void nestedReference(String path, int lineNumber) {
        nestedReferences.add(new InputReference(path, lineNumber));
    }

    public List<InputReference> getAllReferences() {
        List<InputReference> result = new ArrayList<InputReference>(ownReferences.size() + nestedReferences.size());
        result.addAll(ownReferences);
        result.addAll(nestedReferences);
        return result;
    }
}
