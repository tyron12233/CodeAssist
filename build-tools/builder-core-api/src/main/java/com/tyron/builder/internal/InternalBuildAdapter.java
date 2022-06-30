package com.tyron.builder.internal;

import com.tyron.builder.BuildAdapter;
import com.tyron.builder.BuildResult;

public class InternalBuildAdapter extends BuildAdapter implements InternalBuildListener {
    @SuppressWarnings("deprecation")
    @Override
    public void buildFinished(BuildResult result) {
        super.buildFinished(result);
    }
}