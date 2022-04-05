package com.tyron.builder.internal.buildevents;

/**
 * The holder for when the build is considered to have started.
 *
 * This is primarily used to provide user feedback on how long the “build” took (see BuildResultLogger).
 *
 * The build is considered to have started as soon as the user, or some tool, initiated the build.
 * During continuous build, subsequent builds are timed from when changes are noticed.
 */
public class BuildStartedTime {

    private volatile long startTime;

    public static BuildStartedTime startingAt(long startTime) {
        return new BuildStartedTime(startTime);
    }

    public BuildStartedTime(long startTime) {
        this.startTime = startTime;
    }

    public long getStartTime() {
        return startTime;
    }

    public void reset(long startTime) {
        this.startTime = startTime;
    }

}