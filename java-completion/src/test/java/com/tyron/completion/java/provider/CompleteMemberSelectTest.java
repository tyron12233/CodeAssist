package com.tyron.completion.java.provider;

import com.tyron.completion.model.CompletionList;

import org.junit.Test;

public class CompleteMemberSelectTest extends CompletionBase {

    private static final String[] PRIMITIVES = new String[]{"int", "float", "short",
            "long", "double", "char"};

    @Test
    public void testPrimitiveSelect() {
        for (String primitive : PRIMITIVES) {
            String replace = primitive + ".";
            CompletionList list = completeInsertHandle("MemberSelect.java",
                    replace);
            assertCompletion(list, "class");
        }
    }

    @Test
    public void testArraySelect() {
        CompletionList list = completeInsertHandle("MemberSelect.java",
                "ARRAY.");
        assertCompletion(list, "length");
    }

    @Test
    public void testMemberSelect() {
        CompletionList list = completeInsertHandle("MemberSelect.java",
                "select.innerSelect.");
        assertCompletion(list, "innerMethod()");
    }
}
