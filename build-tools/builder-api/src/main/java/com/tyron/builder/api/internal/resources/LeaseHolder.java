package com.tyron.builder.api.internal.resources;

public class LeaseHolder {
    private final int maxWorkerCount;
    private int leasesInUse;

    public LeaseHolder(int maxWorkerCount) {
        this.maxWorkerCount = maxWorkerCount;
    }

    public boolean grantLease() {
        if (leasesInUse >= maxWorkerCount) {
            return false;
        }
        leasesInUse++;
        return true;
    }

    public void releaseLease() {
        leasesInUse--;
    }
}