package com.tyron.builder.api.internal.logging;

import com.google.common.base.Objects;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class Style {
    public static final Style NORMAL = Style.of(Color.DEFAULT);
    public enum Emphasis {
        BOLD, REVERSE, ITALIC
    }

    public enum Color {
        DEFAULT, YELLOW, RED, GREY, GREEN, BLACK
    }

    public final Set<Emphasis> emphasises;
    public final Color color;

    public Style(Set<Emphasis> emphasises, Color color) {
        this.emphasises = emphasises;
        this.color = color;
    }

    public Set<Emphasis> getEmphasises() {
        return emphasises;
    }

    public Color getColor() {
        return color;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        Style rhs = (Style) obj;
        return Objects.equal(getEmphasises(), rhs.getEmphasises())
               && Objects.equal(getColor(), rhs.getColor());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getEmphasises(), getColor());
    }

    public static Style of(Emphasis emphasis) {
        return of(emphasis, Color.DEFAULT);
    }

    public static Style of(Emphasis emphasis, Color color) {
        return new Style(EnumSet.of(emphasis), color);
    }

    public static Style of(Color color) {
        return new Style(Collections.<Emphasis>emptySet(), color);
    }
}