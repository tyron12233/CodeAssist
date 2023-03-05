package org.gradle.cli;

public class ProjectPropertiesCommandLineConverter extends AbstractPropertiesCommandLineConverter{

    @Override
    protected String getPropertyOption() {
        return "P";
    }

    @Override
    protected String getPropertyOptionDetailed() {
        return "project-prop";
    }

    @Override
    protected String getPropertyOptionDescription() {
        return "Set project property for the build script (e.g. -Pmyprop=myvalue).";
    }
}