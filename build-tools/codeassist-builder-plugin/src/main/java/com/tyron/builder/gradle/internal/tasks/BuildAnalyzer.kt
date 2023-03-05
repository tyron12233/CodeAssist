package com.tyron.builder.gradle.internal.tasks

/***
 * Annotation class that is used for Category-Based Task Analyzer for Build Analyzer.
 * All AGP tasks that is an instance of Task::class.java should have this annotation.
 * Exceptions should be declared in the allow-list in [BuildAnalyzerTest]
 */
@Retention(value = AnnotationRetention.RUNTIME)
@Target(allowedTargets = [AnnotationTarget.CLASS])
annotation class BuildAnalyzer(
        /***
         * Main execution category of the task
         */
        val primaryTaskCategory: TaskCategory,
        /***
         * Other possible groupings for the task, does not have to be set
         */
        val secondaryTaskCategories: Array<TaskCategory> = []
)
