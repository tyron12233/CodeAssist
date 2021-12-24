package com.tyron.completion.java.provider;

import com.tyron.completion.model.CompletionList;

import org.junit.Test;

public class CompleteIdentifierTest extends CompletionBase {

    @Test
    public void test() {
        CompletionList list = completeInsertHandle("CompleteIdentifier.java",
                "IDENT");
        assertCompletion(list, "IDENTIFIER");
    }
}
