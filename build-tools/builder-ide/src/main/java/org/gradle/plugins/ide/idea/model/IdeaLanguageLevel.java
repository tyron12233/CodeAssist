package org.gradle.plugins.ide.idea.model;

import com.google.common.base.Objects;
import org.gradle.api.JavaVersion;

/**
 * Java language level used by IDEA projects.
 */
public class IdeaLanguageLevel {

    private String level;

    public IdeaLanguageLevel(Object version) {
        if (version != null && version instanceof String && ((String) version).startsWith("JDK_")) {
            level = (String) version;
            return;
        }
        level = JavaVersion.toVersion(version).name().replaceFirst("VERSION", "JDK");
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!getClass().equals(o.getClass())) {
            return false;
        }
        IdeaLanguageLevel that = (IdeaLanguageLevel) o;
        return Objects.equal(level, that.level);
    }

    @Override
    public int hashCode() {
        return level != null ? level.hashCode() : 0;
    }
}