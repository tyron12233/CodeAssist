package com.tyron.completion;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;

public class TestUtil {

    public static final String MODULE_NAME = "java-completion";

    public static String resolveBasePath() {
        final String path = "./" + MODULE_NAME + "/src/test/resources";
        if (Arrays.asList(Objects.requireNonNull(new File("./").list())).contains(MODULE_NAME)) {
            return path;
        }
        return "../" + path;
    }
}
