package org.jetbrains.kotlin.com.intellij.diagnostic;

public class PluginProblemReporterImpl implements PluginProblemReporter {
    @Override
    public PluginException createPluginExceptionByClass(String s,Throwable throwable, Class aClass) {
        return new PluginException(s, throwable, null);
    }

    public static Class<PluginProblemReporter> getInterface() {
        return PluginProblemReporter.class;
    }
}
