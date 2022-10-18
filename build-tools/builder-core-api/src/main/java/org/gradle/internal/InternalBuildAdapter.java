package org.gradle.internal;

import org.gradle.BuildAdapter;
import org.gradle.BuildResult;

public class InternalBuildAdapter extends BuildAdapter implements InternalBuildListener {
    @SuppressWarnings("deprecation")
    @Override
    public void buildFinished(BuildResult result) {
        super.buildFinished(result);
    }
}