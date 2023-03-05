package org.gradle.cache.internal.filelock;

public class LockInfo {
    public int port = -1;
    public long lockId;
    public String pid = "unknown";
    public String operation = "unknown";
}
