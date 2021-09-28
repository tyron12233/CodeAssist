package com.tyron.builder;

import com.tyron.builder.model.Project;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;



public class TestProject {

    public static final String MODULE_NAME = "build-logic";

    private final Project mProject;

    /**
     * Constructs a new Project Test with the given project name
     * @param name The name of the project located at {@code test/src/resources}
     */
    public TestProject(String name) {
        mProject = new Project(new File(resolveBasePath(), name));
    }

    public Project getProject() {
        return mProject;
    }

    public static String resolveBasePath() {
        final String path = "./" + MODULE_NAME + "/src/test/resources";
        if (Arrays.asList(Objects.requireNonNull(new File("./").list())).contains(MODULE_NAME)) {
            return path;
        }
        return "../" + path;
    }
}
