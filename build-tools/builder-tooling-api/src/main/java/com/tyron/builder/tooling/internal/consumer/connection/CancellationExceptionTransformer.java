/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.tooling.internal.consumer.connection;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.internal.Transformers;
import com.tyron.builder.tooling.internal.consumer.versioning.VersionDetails;
import com.tyron.builder.tooling.internal.protocol.InternalBuildCancelledException;

class CancellationExceptionTransformer implements Transformer<RuntimeException, RuntimeException> {
    private CancellationExceptionTransformer() {
    }

    static Transformer<RuntimeException, RuntimeException> transformerFor(VersionDetails versionDetails) {
        if (versionDetails.honorsContractOnCancel()) {
            return Transformers.noOpTransformer();
        }
        return new CancellationExceptionTransformer();
    }

    @Override
    public RuntimeException transform(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if ("com.tyron.builder.api.BuildCancelledException".equals(t.getClass().getName())
                || "com.tyron.builder.tooling.BuildCancelledException".equals(t.getClass().getName())) {
                return new InternalBuildCancelledException(e.getCause());
            }
        }
        return e;
    }
}
