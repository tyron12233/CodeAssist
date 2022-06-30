package com.tyron.builder.launcher.exec;

import com.tyron.builder.initialization.BuildRequestContext;

/**
 * Marker interface that can be used to obtain the action executer responsible for actually running builds.
 */
public interface BuildExecuter extends BuildActionExecuter<BuildActionParameters, BuildRequestContext> {

}
