package com.tyron.builder.internal.buildoption;

import com.tyron.builder.cli.CommandLineParser;
import com.tyron.builder.cli.ParsedCommandLine;

import java.util.List;
import java.util.Map;

public abstract class BuildOptionSet<T> {
    /**
     * Returns the options defined by this set.
     */
    abstract public List<? extends BuildOption<? super T>> getAllOptions();

//    /**
//     * Returns a {@link CommandLineConverter} that can parse the options defined by this set.
//     */
//    public CommandLineConverter<T> commandLineConverter() {
//        return new AbstractCommandLineConverter<T>() {
//            @Override
//            public T convert(ParsedCommandLine args, T target) throws CommandLineArgumentException {
//                for (BuildOption<? super T> option : getAllOptions()) {
//                    option.applyFromCommandLine(args, target);
//                }
//                return target;
//            }
//
//            @Override
//            public void configure(CommandLineParser parser) {
//                for (BuildOption<? super T> option : getAllOptions()) {
//                    option.configure(parser);
//                }
//            }
//        };
//    }
//
//    /**
//     * Returns a {@link PropertiesConverter} that can extract the options defined by this set.
//     */
//    public PropertiesConverter<T> propertiesConverter() {
//        return new PropertiesConverter<T>() {
//            @Override
//            public T convert(Map<String, String> args, T target) throws CommandLineArgumentException {
//                for (BuildOption<? super T> option : getAllOptions()) {
//                    option.applyFromProperty(args, target);
//                }
//                return target;
//            }
//        };
//    }
}
