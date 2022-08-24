package org.gradle.cli;

public interface CommandLineConverter<T> {
    T convert(Iterable<String> args, T target) throws CommandLineArgumentException;

    T convert(ParsedCommandLine args, T target) throws CommandLineArgumentException;

    void configure(CommandLineParser parser);
}