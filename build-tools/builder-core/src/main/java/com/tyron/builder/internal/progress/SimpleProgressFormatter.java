package com.tyron.builder.internal.progress;

class SimpleProgressFormatter implements ProgressFormatter {
    private final int total;
    private int current;
    private String postfix;

    public SimpleProgressFormatter(int total, String postfix) {
        this.total = total;
        this.postfix = postfix;
    }

    @Override
    public String incrementAndGetProgress() {
        if (current == total) {
            throw new IllegalStateException("Cannot increment beyond the total of: " + total);
        }
        current++;
        return getProgress();
    }

    @Override
    public String getProgress() {
        return current + "/" + total + " " + postfix;
    }
}
