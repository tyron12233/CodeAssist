package com.tyron.builder.api.dsl

import org.gradle.api.Incubating
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

@Incubating
interface SdkComponents {
    /**
     * The path to the Android SDK that Gradle uses for this project.
     *
     * To learn more about downloading and installing the Android SDK, read
     * [Update Your Tools with the SDK Manager](https://developer.android.com/studio/intro/update.html#sdk-manager)
     */
    val sdkDirectory: Provider<Directory>

    /**
     * The path to the [Android NDK](https://developer.android.com/ndk/index.html) that Gradle uses for this project.
     *
     * You can install the Android NDK by either
     * [using the SDK manager](https://developer.android.com/studio/intro/update.html#sdk-manager)
     * or downloading
     * [the standalone NDK package](https://developer.android.com/ndk/downloads/index.html).
     */
    val ndkDirectory: Provider<Directory>

    /**
     * The path to the
     * [Android Debug Bridge (ADB)](https://developer.android.com/studio/command-line/adb.html)
     * executable from the Android SDK.
     */
    val adb: Provider<RegularFile>

    /**
     * The bootclasspath that will be used to compile classes in this project.
     *
     * The returned [Provider] can only be used at execution time and therefore must be used as
     * a [org.gradle.api.Task] input to do so.
     */
    val bootClasspath: Provider<List<RegularFile>>
}
