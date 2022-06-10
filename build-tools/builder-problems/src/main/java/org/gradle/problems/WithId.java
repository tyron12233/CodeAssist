package org.gradle.problems;

public interface WithId<ID extends Enum<ID>> {
    ID getId();
}