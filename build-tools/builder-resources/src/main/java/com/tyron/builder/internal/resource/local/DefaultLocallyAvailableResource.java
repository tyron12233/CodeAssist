package com.tyron.builder.internal.resource.local;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.hash.ChecksumService;

import java.io.File;

public class DefaultLocallyAvailableResource extends AbstractLocallyAvailableResource {
    private final File origin;

    public DefaultLocallyAvailableResource(File origin, ChecksumService checksumService) {
        super(() -> checksumService.sha1(origin));
        this.origin = origin;
    }

    public DefaultLocallyAvailableResource(File origin, HashCode sha1) {
        super(sha1);
        this.origin = origin;
    }

    @Override
    public File getFile() {
        return origin;
    }
}