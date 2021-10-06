package com.tyron.resolver;

import com.tyron.builder.parser.FileManager;
import com.tyron.resolver.model.Dependency;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DependencyUtils {

    private static final Pattern GRADLE_IMPL = Pattern.compile("\\s*(implementation)\\s*(')([a-zA-Z0-9.'/-:\\-]+)(')");
    /**
     * Parses a build.gradle file and gets the dependencies out of it
     * @param file input build.gradle file
     * @return Library dependencies
     */
    public  static List<Dependency> parseGradle(File file) throws Exception {
        String readString = FileUtils.readFileToString(file, Charset.defaultCharset());
        // remove all comments
        readString = readString.replaceAll("\\s*//.*", "");
        Matcher matcher = GRADLE_IMPL.matcher(readString);

        List<Dependency> deps = new ArrayList<>();
        while (matcher.find()) {
            String declaration = matcher.group(3);
            try {
                Dependency dependency = Dependency.from(declaration);
                dependency.setUserDefined(true);
                deps.add(dependency);
            } catch (IllegalArgumentException e) {
                throw new Exception("Cannot parse build.gradle: " + e.getMessage());
            }
        }
        return deps;
    }

    /**
     * Get list of dependency from app/libs folder
     * @param libsDir app's library folder
     * @return list of dependencies
     */
    public static Set<Dependency> fromLibs(File libsDir) {
        File[] childs = libsDir.listFiles();
        Set<Dependency> dependencies = new HashSet<>();
        if (childs == null) {
            return dependencies;
        }
        for (File child : childs) {
            if (!child.isFile()) {
                continue;
            }

            String name = child.getName().substring(0, child.getName().lastIndexOf("."));
            try {
                Dependency dependency = Dependency.from(name);
                dependencies.add(dependency);
            } catch (IllegalArgumentException ignore) {

            }
        }
        return dependencies;
    }
}
