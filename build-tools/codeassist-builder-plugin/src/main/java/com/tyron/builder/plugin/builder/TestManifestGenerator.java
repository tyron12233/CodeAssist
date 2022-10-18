package com.tyron.builder.plugin.builder;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
/**
 * Generate an AndroidManifest.xml file for test projects.
 */
public class TestManifestGenerator {
    private final static String TEMPLATE = "AndroidManifest.template";
    private final static String PH_PACKAGE = "#PACKAGE#";
    private final static String PH_TESTED_PACKAGE = "#TESTEDPACKAGE#";
    private final static String PH_TEST_RUNNER = "#TESTRUNNER#";
    private final String mOutputFile;
    private final String mPackageName;
    private final String mTestedPackageName;
    private final String mTestRunnerName;
    TestManifestGenerator(@NotNull String outputFile,
                          @NotNull String packageName,
                          @NotNull String testedPackageName,
                          @NotNull String testRunnerName) {
        mOutputFile = outputFile;
        mPackageName = packageName;
        mTestedPackageName = testedPackageName;
        mTestRunnerName = testRunnerName;
    }
    public void generate() throws IOException {
        Map<String, String> map = new HashMap<String, String>();
        map.put(PH_PACKAGE, mPackageName);
        map.put(PH_TESTED_PACKAGE, mTestedPackageName);
        map.put(PH_TEST_RUNNER, mTestRunnerName);
        TemplateProcessor processor = new TemplateProcessor(
                TestManifestGenerator.class.getResourceAsStream(TEMPLATE),
                map);
        processor.generate(new File(mOutputFile));
    }
}