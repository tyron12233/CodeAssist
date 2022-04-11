package com.tyron.builder.initialization;

/**
 * A bunch of information about the client used to start this build.
 */
public interface BuildClientMetaData {
    /**
     * Appends a message to the given appendable describing how to run the client with the given command-line args.
     */
    void describeCommand(Appendable output, String... args);
}