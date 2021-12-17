package com.tyron.code.util;

import com.tyron.builder.log.ILogger;
import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.repository.PomRepository;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
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
    public static List<Pom> parseGradle(PomRepository repository, File file, ILogger logger) throws IOException {
        String readString = FileUtils.readFileToString(file, Charset.defaultCharset());
        // remove all comments
        readString = readString.replaceAll("\\s*//.*", "");
        Matcher matcher = GRADLE_IMPL.matcher(readString);

        List<Pom> deps = new ArrayList<>();
        while (matcher.find()) {
            String declaration = matcher.group(3);
            if (declaration != null) {

                Pom dependency = repository.getPom(declaration);
                if (dependency != null) {
                    //dependency.setUserDefined(true);
                    deps.add(dependency);
                } else {
                    logger.warning("Unable to resolve dependency: " + declaration);
                }
            }
        }

        matcher = GRADLE_IMPL_QUOT.matcher(readString);
        while (matcher.find()) {
            String declaration = matcher.group(3);
            Pom dependency = repository.getPom(declaration);
            if (dependency != null) {
                //  dependency.setUserDefined(true);
                deps.add(dependency);
            } else {
                logger.warning("Unable to resolve dependency: " + declaration);
            }
        }
        return deps;
    }
}

