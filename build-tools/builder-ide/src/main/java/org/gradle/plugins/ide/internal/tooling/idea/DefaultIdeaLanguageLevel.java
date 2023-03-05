package org.gradle.plugins.ide.internal.tooling.idea;

import com.google.common.base.Objects;
import org.gradle.tooling.model.idea.IdeaLanguageLevel;

import java.io.Serializable;

public class DefaultIdeaLanguageLevel implements IdeaLanguageLevel, Serializable {

    private final String level;

    public DefaultIdeaLanguageLevel(String level) {
        this.level = level;
    }

    public boolean isJDK_1_4() {
        return "JDK_1_4".equals(level);
    }

    public boolean isJDK_1_5() {
        return "JDK_1_5".equals(level);
    }

    public boolean isJDK_1_6() {
        return "JDK_1_6".equals(level);
    }

    public boolean isJDK_1_7() {
        return "JDK_1_7".equals(level);
    }

    public boolean isJDK_1_8() {
        return "JDK_1_8".equals(level);
    }

    @Override
    public String getLevel() {
        return level;
    }

    @Override
    public String toString() {
        return "IdeaLanguageLevel{level='" + level + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultIdeaLanguageLevel)) {
            return false;
        }

        DefaultIdeaLanguageLevel that = (DefaultIdeaLanguageLevel) o;
        return Objects.equal(level, that.level);
    }

    @Override
    public int hashCode() {
        return level != null ? level.hashCode() : 0;
    }
}
