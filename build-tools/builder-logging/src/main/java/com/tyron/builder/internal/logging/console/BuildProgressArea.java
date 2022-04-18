package com.tyron.builder.internal.logging.console;

import java.util.List;

public interface BuildProgressArea {
    // TODO(ew): Consider whether this belongs in Console or here
    StyledLabel getProgressBar();
    List<StyledLabel> getBuildProgressLabels();
    void resizeBuildProgressTo(int numberOfLabels);
    void setVisible(boolean isVisible);
}
