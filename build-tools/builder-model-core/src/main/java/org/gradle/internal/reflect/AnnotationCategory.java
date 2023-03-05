package org.gradle.internal.reflect;

import org.gradle.api.Describable;

public interface AnnotationCategory extends Describable {
    AnnotationCategory TYPE = new AnnotationCategory() {
        @Override
        public String getDisplayName() {
            return "type";
        }

        @Override
        public String toString() {
            return "TYPE";
        }
    };
}
