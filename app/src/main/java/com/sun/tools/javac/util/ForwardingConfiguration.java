package com.sun.tools.javac.util;

import java.util.Set;

import static com.sun.tools.javac.api.DiagnosticFormatter.Configuration;

/**
 * A delegated formatter configuration delegates all configurations settings
 * to an underlying configuration object (aka the delegated configuration).
 */
public class ForwardingConfiguration implements Configuration {

    /**
     * The configurationr object to which the forwarding configuration delegates some settings
     */
    protected Configuration configuration;

    public ForwardingConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    /**
     * Returns the underlying delegated configuration.
     *
     * @return delegated configuration
     */
    public Configuration getDelegatedConfiguration() {
        return configuration;
    }

    public int getMultilineLimit(MultilineLimit limit) {
        return configuration.getMultilineLimit(limit);
    }

    public Set<DiagnosticPart> getVisible() {
        return configuration.getVisible();
    }

    public void setMultilineLimit(MultilineLimit limit, int value) {
        configuration.setMultilineLimit(limit, value);
    }

    public void setVisible(Set<DiagnosticPart> diagParts) {
        configuration.setVisible(diagParts);
    }
}