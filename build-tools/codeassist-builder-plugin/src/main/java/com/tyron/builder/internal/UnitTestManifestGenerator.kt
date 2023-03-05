package com.tyron.builder.internal

import java.io.File

class UnitTestManifestGenerator(
    outputFile: File,
    packageName: String,
    minSdkVersion: String?,
    targetSdkVersion: String?,
    testedPackageName: String,
    testRunnerName: String?,
): TestManifestGenerator(
    outputFile,
    packageName,
    minSdkVersion,
    targetSdkVersion,
    testedPackageName,
    testRunnerName
) {

    override val templateResourceName: String =
        "AndroidManifest.UnitTestTemplate"
}
