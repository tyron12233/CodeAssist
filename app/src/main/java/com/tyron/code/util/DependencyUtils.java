package com.tyron.code.util;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.tyron.builder.log.ILogger;
import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.PomRepository;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DependencyUtils {

    public static List<Dependency> parseLibraries(File libraries, ILogger logger) {
        String contents;
        try {
            contents = FileUtils.readFileToString(libraries, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Unable to read " + libraries.getName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
        try {
            List<Dependency> dependencies = new Gson().fromJson(contents,
                    new TypeToken<List<Dependency>>() {}.getType());
            if (dependencies == null) {
                dependencies = Collections.emptyList();
            }
            return dependencies;
        } catch (JsonSyntaxException e) {
            logger.error("Unable to parse " + libraries.getName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }
}

