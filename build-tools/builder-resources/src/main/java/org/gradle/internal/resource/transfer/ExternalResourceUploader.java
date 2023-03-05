package org.gradle.internal.resource.transfer;

import org.gradle.internal.resource.ExternalResource;
import org.gradle.internal.resource.ExternalResourceName;
import org.gradle.internal.resource.ReadableContent;

import java.io.IOException;

/**
 * You should use {@link ExternalResource} instead of this type.
 */
public interface ExternalResourceUploader {
    void upload(ReadableContent resource, ExternalResourceName destination) throws IOException;
}