package com.tyron.builder.gradle.internal.packaging

// ATTENTION - keep this in sync with com.android.build.gradle.internal.dsl.PackagingOptions JavaDoc
val defaultExcludes: Set<String> = setOf(
    "/META-INF/LICENSE",
    "/META-INF/LICENSE.txt",
    "/META-INF/MANIFEST.MF",
    "/META-INF/NOTICE",
    "/META-INF/NOTICE.txt",
    "/META-INF/*.DSA",
    "/META-INF/*.EC",
    "/META-INF/*.SF",
    "/META-INF/*.RSA",
    "/META-INF/maven/**",
    "/META-INF/proguard/*",
    "/META-INF/com.android.tools/**",
    "/NOTICE",
    "/NOTICE.txt",
    "/LICENSE.txt",
    "/LICENSE",

    // Exclude version control folders.
    "**/.svn/**",
    "**/CVS/**",
    "**/SCCS/**",

    // Exclude hidden and backup files.
    "**/.*/**",
    "**/.*",
    "**/*~",

    // Exclude index files
    "**/thumbs.db",
    "**/picasa.ini",

    // Exclude javadoc files
    "**/about.html",
    "**/package.html",
    "**/overview.html",

    // Exclude protobuf metadata files
    "**/protobuf.meta",

    // Exclude stuff for unknown reasons
    "**/_*",
    "**/_*/**",

    // Exclude kotlin metadata files
    "**/*.kotlin_metadata"
)

// ATTENTION - keep this in sync with com.android.build.gradle.internal.dsl.PackagingOptions JavaDoc
val defaultMerges: Set<String> = setOf("/META-INF/services/**", "jacoco-agent.properties")