package com.tyron.test;

/**
 * Tests that members are visible to its subclasses
 */
public class InheritedMembers {

    public class Parent {
        protected final Object PARENT_OBJECT = new Object();

        private void privateMethod() {

        }

        protected void protectedMethod() {

        }

        protected static void protectedStaticMethod() {

        }
    }

    public class Child extends Parent {
        public void instanceMethod() {
            /** @insert */
        }
    }
}