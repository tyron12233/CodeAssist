package com.tyron.test;

public class MemberSelect {

    private static final Object[] ARRAY = new Object[0];

    public static class InnerSelect {
        public void innerMethod() {}
    }
    public static class Select {
        public InnerSelect innerSelect;
    }

    public void main() {
        Select select = new Select();
        /** @insert */
    }
}