/*
 * Copyright 2007-2008 the original author or authors.
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
package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.PublishException;
import com.tyron.builder.api.internal.artifacts.repositories.PublicationAwareRepository;

import java.io.File;

/**
 * Service used by `Upload` task for publishing
 */
public interface ArtifactPublisher {
    void publish(Iterable<? extends PublicationAwareRepository> repositories, Module module, Configuration configuration, File descriptor) throws PublishException;
}
