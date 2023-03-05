package org.gradle.internal.buildoption;

import org.gradle.cli.CommandLineArgumentException;

import java.util.Map;

public interface PropertiesConverter<T> {
    T convert(Map<String, String> args, T target) throws CommandLineArgumentException;
}