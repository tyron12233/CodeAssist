package com.tyron.builder.internal.build;

/**
 * A stand alone nested build, which is a nested build that runs as part of some containing build as a single atomic step, without participating in task execution of the containing build.
 */
public interface StandAloneNestedBuild extends NestedBuildState, BuildActionTarget {
}