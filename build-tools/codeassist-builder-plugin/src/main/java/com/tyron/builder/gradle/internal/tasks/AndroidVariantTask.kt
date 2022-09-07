package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.tasks.BaseTask
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault

/**
 * Base Android task with a variant name and support for analytics
 *
 * DO NOT EXTEND THIS METHOD DIRECTLY. Instead extend:
 * - [NewIncrementalTask]
 * - [NonIncrementalTask]
 *
 */
@DisableCachingByDefault
abstract class AndroidVariantTask : BaseTask(), VariantAwareTask {

    @Internal("No influence on output, this is for our build stats reporting mechanism")
    override lateinit var variantName: String
}
