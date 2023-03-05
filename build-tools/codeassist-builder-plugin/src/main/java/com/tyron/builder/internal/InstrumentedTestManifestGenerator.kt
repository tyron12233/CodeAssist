package com.tyron.builder.internal

import java.io.File

/**
 * Generate an AndroidManifest.xml file for test projects.
 */
class InstrumentedTestManifestGenerator(
    outputFile: File,
    packageName: String,
    minSdkVersion: String?,
    targetSdkVersion: String?,
    testedPackageName: String,
    testRunnerName: String,
    private val handleProfiling: Boolean,
    private val functionalTest: Boolean
): TestManifestGenerator(outputFile, packageName, minSdkVersion, targetSdkVersion, testedPackageName, testRunnerName) {

    override fun populateTemplateParameters(map: MutableMap<String, String?>) {
        super.populateTemplateParameters(map)
        map[PH_HANDLE_PROFILING] = java.lang.Boolean.toString(handleProfiling)
        map[PH_FUNCTIONAL_TEST] = java.lang.Boolean.toString(functionalTest)
    }

    override val templateResourceName: String = TEMPLATE

    companion object {
        private const val TEMPLATE = "AndroidManifest.template"
        private const val PH_HANDLE_PROFILING = "#HANDLEPROFILING#"
        private const val PH_FUNCTIONAL_TEST = "#FUNCTIONALTEST#"
    }
}
