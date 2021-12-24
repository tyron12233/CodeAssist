package com.tyron.completion.model;

public class Range {
    public Position start, end;

    public Range(long startPosition, long endPosition) {
        start = new Position(startPosition, startPosition);
        end = new Position(endPosition, endPosition);
    }

    public Range(Position start, Position end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }

    public static final Range NONE = new Range(Position.NONE, Position.NONE);
}