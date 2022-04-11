package com.tyron.builder.api.internal;

import com.google.common.base.Objects;
import com.tyron.builder.api.Describable;

public class Describables {
    private Describables() {
    }

    /**
     * Returns a describable that converts the provided value to a string each time the display name is queried. Can pass a {@link Describable} or {@link DisplayName}.
     */
    public static DisplayName of(Object displayName) {
        if (displayName instanceof DisplayName) {
            return (DisplayName) displayName;
        }
        return new FixedDescribable(displayName);
    }

    private static abstract class AbstractDescribable implements DisplayName {
        @Override
        public String toString() {
            return getDisplayName();
        }
    }

    private static class FixedDescribable extends AbstractDescribable {
        private final Object displayName;

        FixedDescribable(Object displayName) {
            this.displayName = displayName;
        }

        @Override
        public String getDisplayName() {
            if (displayName instanceof CharSequence) {
                return displayName.toString();
            }
            StringBuilder builder = new StringBuilder(32);
            appendDisplayName(displayName, builder);
            return builder.toString();
        }

        @Override
        public String getCapitalizedDisplayName() {
            StringBuilder builder = new StringBuilder();
            appendCapDisplayName(displayName, builder);
            return builder.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            FixedDescribable that = (FixedDescribable) o;
            return Objects.equal(displayName, that.displayName);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(displayName);
        }
    }

    private static void appendDisplayName(Object src, StringBuilder stringBuilder) {
        if (src instanceof Describable) {
            Describable describable = (Describable) src;
            stringBuilder.append(describable.getDisplayName());
        } else {
            stringBuilder.append(src.toString());
        }
    }

    private static void appendCapDisplayName(Object src, StringBuilder stringBuilder) {
        if (src instanceof DisplayName) {
            DisplayName displayName = (DisplayName) src;
            stringBuilder.append(displayName.getCapitalizedDisplayName());
        } else {
            int pos = stringBuilder.length();
            if (src instanceof Describable) {
                Describable describable = (Describable) src;
                stringBuilder.append(describable.getDisplayName());
            } else {
                stringBuilder.append(src.toString());
            }
            stringBuilder.setCharAt(pos, Character.toUpperCase(stringBuilder.charAt(pos)));
        }
    }
}
