package com.tyron.builder.api.internal.reflect;

import com.tyron.builder.api.Describable;

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
