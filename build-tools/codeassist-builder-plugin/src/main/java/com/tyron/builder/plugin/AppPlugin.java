package com.tyron.builder.plugin;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Plugin;

public class AppPlugin implements Plugin<BuildProject> {

    @Override
    public void apply(BuildProject target) {
        String message = "CodeAssist does not yet support the Android Gradle Plugin." +
                         " It is currently being worked on but it is not yet available for use."  +
                         "The only supported plugin for now is the `java` plugin which ise used " +
                         "for building JVM applications";
        throw new BuildException(message);
    }
}
