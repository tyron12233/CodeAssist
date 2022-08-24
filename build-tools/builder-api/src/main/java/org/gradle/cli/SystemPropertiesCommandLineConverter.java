package org.gradle.cli;

public class SystemPropertiesCommandLineConverter extends AbstractPropertiesCommandLineConverter {

    @Override
    protected String getPropertyOption() {
        return "D";
    }

    @Override
    protected String getPropertyOptionDetailed() {
        return "system-prop";
    }

    @Override
    protected String getPropertyOptionDescription() {
        return "Set system property of the JVM (e.g. -Dmyprop=myvalue).";
    }
}