package com.tyron.completion.java.provider;

import static com.google.common.truth.Truth.assertThat;

import com.tyron.completion.model.CompletionList;

import org.junit.Test;

import java.util.stream.Collectors;

public class CompleteInheritedMemberTest extends CompletionBase {

    @Test
    public void testCompleteInheritedMember() {
        CompletionList list = completeInsertHandle("InheritedMembers.java",
                "PARENT");
        assertCompletion(list, "PARENT_OBJECT");
    }

    @Test
    public void testPrivateMethodShouldNotBeVisible() {
        CompletionList list = completeInsertHandle("InheritedMembers.java",
                "priv");
        assertThat(list.items.stream().map(l -> l.label).collect(Collectors.toList()))
                .doesNotContain("privateMethod()");
    }

    @Test
    public void testProtectedMethodShouldBeVisible() {
        CompletionList list = completeInsertHandle("InheritedMembers.java",
                "prote");
        assertCompletion(list, "protectedMethod()");
    }

    @Test
    public void testProtectedStaticMethodShouldBeVisible() {
        CompletionList list = completeInsertHandle("InheritedMembers.java",
                "prote");
        assertCompletion(list, "protectedStaticMethod()");
    }
}
