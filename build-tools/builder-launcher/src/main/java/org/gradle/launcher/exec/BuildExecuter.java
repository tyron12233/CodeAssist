package org.gradle.launcher.exec;

import org.gradle.initialization.BuildRequestContext;

/**
 * Marker interface that can be used to obtain the action executer responsible for actually running builds.
 */
public interface BuildExecuter extends BuildActionExecuter<BuildActionParameters, BuildRequestContext> {

}
