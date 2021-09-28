package com.tyron.builder;

import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;



public class TestProject {

    public static final String MODULE_NAME = "build-logic";

    private static final String MAIN_CLASS = "package com.tyron.test;\n\n" +
            "import android.app.Activity;\n" +
            "import android.os.Bundle;\n" +
            "\n" +
            "public class MainActivity extends Activity {\n" +
            "\n" +
            "    @Override\n" +
            "    protected void onCreate(Bundle savedInstanceState) {\n" +
            "        super.onCreate(savedInstanceState);\n" +
            "    }\n" +
            "}";

    private final Project mProject;

    /**
     * Constructs a new Project Test with the given project name
     * @param name The name of the project located at {@code test/src/resources}
     */
    public TestProject(String name) {
        mProject = new Project(new File(resolveBasePath(), name));
        deleteJavaFiles();
        javaFile("com.tyron.test", "MainActivity", MAIN_CLASS);

        mProject.clear();
        FileManager.getInstance().openProject(mProject);
    }

    private void deleteBuildDirectory() {
        FileUtils.deleteQuietly(mProject.getBuildDirectory());
    }
    private void deleteJavaFiles() {
        FileUtils.deleteQuietly(mProject.getJavaDirectory());
    }

    /**
     * Creates a new java file for this project
     * @param packageName The package name on where to create this class
     * @param name Name of the java file without the extension
     * @param contents The actual String contents of the class
     *
     * @return The file that was created
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public File javaFile(String packageName, String name, String contents) {
        String packageDirStr = "/" + packageName.replace(".", "/");
        File packageDir = new File(mProject.getJavaDirectory(), packageDirStr);
        if (!packageDir.exists() && !packageDir.mkdirs()) {
            throw new RuntimeException("Unable to create java class file");
        }

        File javaClass = new File(new File(mProject.getJavaDirectory(), packageDirStr), name + ".java");
        if (!javaClass.exists()) {
            try {
                javaClass.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("Unable to create java class file", e);
            }
        }

        try {
            FileUtils.writeStringToFile(javaClass, contents, Charset.defaultCharset());
        } catch (IOException e) {
            throw new RuntimeException("Unable to write to jav class file", e);
        }

        mProject.javaFiles.put(packageName + "." + name, javaClass);

        return javaClass;
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
