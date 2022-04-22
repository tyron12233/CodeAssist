package com.tyron.builder.internal.watch.registry;

import java.io.File;
import java.util.stream.Stream;

/**
 * A registry of watch probes. A probe serves as a way to prove that a hierarchy Gradle is interested in
 * indeed receives file system events from the operating system.
 * This is to avoid trusting locations where OSs silently not send any events, despite watchers being registered.
 *
 * When the hierarchy is first registered via {@link #registerProbe(File)}, we don't yet create the probe.
 * That only happens when the hierarchy is actually read or written by Gradle, in which case
 * {@link #armWatchProbe(File)} is called.
 *
 * When the probe is armed, a probe file is created (or re-created) under the hierarchy.
 * This should cause a file system event to be produced by the operating system.
 * We listen to those events specifically in {@link FileWatcherRegistry}.
 * Once the event arrives, {@link #triggerWatchProbe(String)} is called with the path,
 * and the probe becomes triggered (or proven).
 *
 * The {@link #unprovenHierarchies()} stream returns any hierarchies that were armed, but never received
 * a file system event.
 * These locations cannot be trusted to receive file system events in a timely manner.
 *
 * Note: a probe needs to be armed only once, if it receives an event, we will trust that location until
 * the registry is closed.
 */
public interface FileWatcherProbeRegistry {
    void registerProbe(File hierarchy);

    Stream<File> unprovenHierarchies();

    void armWatchProbe(File watchableHierarchy);

    void disarmWatchProbe(File watchableHierarchy);

    void triggerWatchProbe(String path);

    File getProbeDirectory(File hierarchy);
}
