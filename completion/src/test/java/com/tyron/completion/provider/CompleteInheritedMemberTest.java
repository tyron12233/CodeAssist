package com.tyron.completion.provider;

import com.tyron.completion.model.CompletionList;

import org.junit.Test;

public class CompleteInheritedMemberTest extends CompletionBase {

    @Test
    public void testCompleteInheritedMember() {
        CompletionList list = completeInsertHandle("InheritedMembers.java",
                "PARENT");
        assertCompletion(list, "PARENT_OBJECT");
    }
}
