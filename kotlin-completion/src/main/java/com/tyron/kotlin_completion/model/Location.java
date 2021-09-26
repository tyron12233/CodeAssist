package com.tyron.kotlin_completion.model;

import com.tyron.completion.model.Range;

public class Location {

    private String uri;

    private Range range;

    public Location() {}

    public Location(String uri, Range range) {
        this.uri = uri;
        this.range = range;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Range getRange() {
        return range;
    }

    public void setRange(Range range) {
        this.range = range;
    }
}
