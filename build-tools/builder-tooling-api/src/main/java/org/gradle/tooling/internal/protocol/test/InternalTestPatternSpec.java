package org.gradle.tooling.internal.protocol.test;

import java.util.List;
import java.util.Map;

/**
 * Specifies a test pattern
 *
 * DO NOT CHANGE THIS INTERFACE. It is part of the cross-version protocol.
 *
 * @since 7.6
 */
public interface InternalTestPatternSpec {
    String getTaskPath();
    List<String> getPackages();
    List<String> getClasses();
    Map<String, List<String>> getMethods();
    List<String> getPatterns();
}