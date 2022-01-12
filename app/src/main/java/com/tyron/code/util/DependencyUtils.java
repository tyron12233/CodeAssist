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

    private static final Pattern GRADLE_IMPL = Pattern.compile("\\s*(implementation)\\s*(')([a-zA-Z0-9.'/-:\\-]+)(')");
    private static final Pattern GRADLE_IMPL_QUOT = Pattern.compile("\\s*(implementation)\\s*(\")([a-zA-Z0-9.'/-:\\-]+)(\")");

    /**
     * Parses a build.gradle file and gets the dependencies out of it
     *
     * @param file input build.gradle file
     * @return Library dependencies
     */
    public static List<Dependency> parseGradle(PomRepository repository, File file, ILogger logger) throws IOException {
        String readString = FileUtils.readFileToString(file, Charset.defaultCharset());
        // remove all comments
        readString = readString.replaceAll("\\s*//.*", "");
        Matcher matcher = GRADLE_IMPL.matcher(readString);

        List<Dependency> deps = new ArrayList<>();
        while (matcher.find()) {
            String declaration = matcher.group(3);
            if (declaration != null) {
                deps.add(Dependency.valueOf(declaration));
            }
        }

        matcher = GRADLE_IMPL_QUOT.matcher(readString);
        while (matcher.find()) {
            String declaration = matcher.group(3);
            if (declaration != null) {
                deps.add(Dependency.valueOf(declaration));
            }
        }
        return deps;
    }

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

