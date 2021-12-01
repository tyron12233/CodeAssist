package com.tyron.test;

/**
 * Tests that members are visible to its subclasses
 */
public class InheritedMembers {

    public class Parent {
        protected final Object PARENT_OBJECT = new Object();
    }

    public class Child extends Parent {
        public void instanceMethod() {
            /** @insert */
        }
    }
}