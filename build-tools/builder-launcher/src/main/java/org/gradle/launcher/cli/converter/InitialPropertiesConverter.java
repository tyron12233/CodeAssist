package org.gradle.launcher.cli.converter;

import org.gradle.cli.CommandLineConverter;
import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.gradle.cli.SystemPropertiesCommandLineConverter;
import org.gradle.launcher.configuration.InitialProperties;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class InitialPropertiesConverter {
    private final CommandLineConverter<Map<String, String>> systemPropertiesCommandLineConverter = new SystemPropertiesCommandLineConverter();

    public void configure(CommandLineParser parser) {
        systemPropertiesCommandLineConverter.configure(parser);
    }

    public InitialProperties convert(ParsedCommandLine commandLine) {
        Map<String, String> requestedSystemProperties = systemPropertiesCommandLineConverter.convert(commandLine, new HashMap<>());

        return new InitialProperties() {
            @Override
            public Map<String, String> getRequestedSystemProperties() {
                return Collections.unmodifiableMap(requestedSystemProperties);
            }
        };
    }
}