package com.tyron.builder.api.internal.os;


public class OperatingSystem {

    private static OperatingSystem INSTANCE;

    public static OperatingSystem current() {
        if (INSTANCE == null) {
            INSTANCE = new OperatingSystem();
        }

        return INSTANCE;
    }

    private final boolean isWindows;

    private OperatingSystem() {
        isWindows = System.getProperty("os.name", "")
                .startsWith("Windows");
    }

    public boolean isWindows() {
        return isWindows;
    }
}
