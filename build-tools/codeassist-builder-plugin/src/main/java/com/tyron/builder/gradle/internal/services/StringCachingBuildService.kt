package com.tyron.builder.gradle.internal.services

import com.tyron.builder.internal.StringCachingService
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Build service used to cache string.
 *
 * This allows calling code to de-duplicate strings that are constructed dynamically, by returning
 * a similar string already used elsewhere.
 *
 * This can be used anywhere we expect strings to be kept in memory with large number of similar
 * strings.
 */
abstract class StringCachingBuildService : BuildService<BuildServiceParameters.None>,
    AutoCloseable, StringCachingService {

    private val strings = mutableMapOf<String, String>()

    @Synchronized
    override fun cacheString(string: String): String {
        val existingString = strings[string]
        return if (existingString == null) {
            strings[string] = string
            string
        } else {
            existingString
        }
    }

    @Synchronized
    override fun close() {
        strings.clear()
    }

    class RegistrationAction(project: Project) :
        ServiceRegistrationAction<StringCachingBuildService, BuildServiceParameters.None>(
            project,
            StringCachingBuildService::class.java
        ) {
        override fun configure(parameters: BuildServiceParameters.None) {
            // do nothing
        }
    }
}