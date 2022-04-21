package com.tyron.builder.internal.nativeintegration.console;


public enum FallbackConsoleMetaData implements ConsoleMetaData {
    ATTACHED(true),
    NOT_ATTACHED(false);

    private final boolean attached;

    FallbackConsoleMetaData(boolean attached) {
        this.attached = attached;
    }

    @Override
    public boolean isStdOut() {
        return attached;
    }

    @Override
    public boolean isStdErr() {
        return attached;
    }

    @Override
    public int getCols() {
        return 0;
    }

    @Override
    public int getRows() {
        return 0;
    }

    @Override
    public boolean isWrapStreams() {
        return attached;
    }
}
