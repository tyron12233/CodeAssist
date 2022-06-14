package com.tyron.builder.internal.progress;

public class PercentageProgressFormatter implements ProgressFormatter {
    private int current;
    private int total;
    private String prefix;

    public PercentageProgressFormatter(String prefix, int total) {
        this.total = total;
        this.prefix = prefix;
    }

    @Override
    public String incrementAndGetProgress() {
        increment();
        return getProgress();
    }

    public void increment() {
        if (current == total) {
            throw new IllegalStateException("Cannot increment beyond the total of: " + total);
        }
        current++;
    }

    @Override
    public String getProgress() {
        return prefix + " " + (int) (current * 100.0 / total) + "%";
    }
}
