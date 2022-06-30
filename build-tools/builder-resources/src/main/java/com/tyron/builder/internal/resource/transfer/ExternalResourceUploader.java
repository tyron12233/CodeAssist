package com.tyron.builder.internal.resource.transfer;

import com.tyron.builder.internal.resource.ExternalResource;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ReadableContent;

import java.io.IOException;

/**
 * You should use {@link ExternalResource} instead of this type.
 */
public interface ExternalResourceUploader {
    void upload(ReadableContent resource, ExternalResourceName destination) throws IOException;
}