package com.tyron.completion.xml.v2

import com.tyron.completion.xml.v2.aar.FrameworkResourceRepository
import org.junit.Test
import java.nio.file.Paths

class FrameworkResourcesTest {

    @Test
    fun `test loading framework resources from directory`(){
        val frameworkResJar = Paths.get("C:/Users/tyron scott/AppData/Local/Android/Sdk/platforms/android-32/data/res")
        val frameworkResRepository = FrameworkResourceRepository.create(
            frameworkResJar, setOf("en"),
            null,
            true
        )
        assert(frameworkResRepository.allResources.isNotEmpty())
    }
}