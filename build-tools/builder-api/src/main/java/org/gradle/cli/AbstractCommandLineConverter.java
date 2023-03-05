package org.gradle.cli;

public abstract class AbstractCommandLineConverter<T> implements CommandLineConverter<T> {
    public T convert(Iterable<String> args, T target) throws CommandLineArgumentException {
        CommandLineParser parser = new CommandLineParser();
        configure(parser);
        return convert(parser.parse(args), target);
    }
}