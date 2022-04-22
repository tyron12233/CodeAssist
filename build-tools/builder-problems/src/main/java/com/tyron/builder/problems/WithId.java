package com.tyron.builder.problems;

public interface WithId<ID extends Enum<ID>> {
    ID getId();
}