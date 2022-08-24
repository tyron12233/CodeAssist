package org.gradle.cli;

import java.util.Map;

public abstract class AbstractPropertiesCommandLineConverter extends AbstractCommandLineConverter<Map<String, String>> {
    protected abstract String getPropertyOption();
    protected abstract String getPropertyOptionDetailed();
    protected abstract String getPropertyOptionDescription();

    public void configure(CommandLineParser parser) {
        CommandLineOption option = parser.option(getPropertyOption(), getPropertyOptionDetailed());
        option = option.hasArguments();
        option.hasDescription(getPropertyOptionDescription());
    }

    public Map<String, String> convert(ParsedCommandLine options, Map<String, String> properties) throws CommandLineArgumentException {
        for (String keyValueExpression : options.option(getPropertyOption()).getValues()) {
            int pos = keyValueExpression.indexOf("=");
            if (pos < 0) {
                properties.put(keyValueExpression, "");
            } else {
                properties.put(keyValueExpression.substring(0, pos), keyValueExpression.substring(pos+1));
            }
        }
        return properties;
    }
}