/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.internal.resource.transfer;

import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationInvocationException;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ExternalResourceWriteBuildOperationType;
import com.tyron.builder.internal.resource.ReadableContent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class ProgressLoggingExternalResourceUploader extends AbstractProgressLoggingHandler implements ExternalResourceUploader {
    private final ExternalResourceUploader delegate;
    private final BuildOperationExecutor buildOperationExecutor;

    public ProgressLoggingExternalResourceUploader(ExternalResourceUploader delegate, BuildOperationExecutor buildOperationExecutor) {
        this.delegate = delegate;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public void upload(final ReadableContent resource, ExternalResourceName destination) throws IOException {
        try {
            buildOperationExecutor.run(new UploadOperation(destination, resource));
        } catch (BuildOperationInvocationException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
    }

    private static class ProgressLoggingReadableContent implements ReadableContent {
        private final ReadableContent delegate;
        private final ResourceOperation uploadOperation;

        private ProgressLoggingReadableContent(ReadableContent delegate, ResourceOperation uploadOperation) {
            this.delegate = delegate;
            this.uploadOperation = uploadOperation;
        }

        @Override
        public InputStream open() {
            return new ProgressLoggingInputStream(delegate.open(), uploadOperation);
        }

        @Override
        public long getContentLength() {
            return delegate.getContentLength();
        }
    }

    private static class PutOperationDetails extends LocationDetails implements ExternalResourceWriteBuildOperationType.Details {
        private PutOperationDetails(URI location) {
            super(location);
        }

        @Override
        public String toString() {
            return "ExternalResourceWriteBuildOperationType.Details{location=" + getLocation() + ", " + '}';
        }
    }

    private class UploadOperation implements RunnableBuildOperation {
        private final ExternalResourceName destination;
        private final ReadableContent resource;

        public UploadOperation(ExternalResourceName destination, ReadableContent resource) {
            this.destination = destination;
            this.resource = resource;
        }

        @Override
        public void run(BuildOperationContext context) throws IOException {
            ResourceOperation uploadOperation = createResourceOperation(context, ResourceOperation.Type.upload);
            uploadOperation.setContentLength(resource.getContentLength());
            try {
                delegate.upload(new ProgressLoggingReadableContent(resource, uploadOperation), destination);
            } finally {
                context.setResult(new ExternalResourceWriteBuildOperationType.Result() {
                    @Override
                    public long getBytesWritten() {
                        return uploadOperation.getTotalProcessedBytes();
                    }
                });
            }
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            return BuildOperationDescriptor
                .displayName("Upload " + destination.getUri())
                .progressDisplayName(destination.getShortDisplayName())
                .details(new PutOperationDetails(destination.getUri()));
        }
    }
}
