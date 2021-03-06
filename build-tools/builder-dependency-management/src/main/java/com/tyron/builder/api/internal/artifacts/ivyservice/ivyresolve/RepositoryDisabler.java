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

package com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve;

import java.util.Collections;
import java.util.Set;

public interface RepositoryDisabler {

    boolean isDisabled(String repositoryId);

    boolean disableRepository(String repositoryId, Throwable throwable);

    Set<String> getDisabledRepositories();

    enum NoOpBlacklister implements RepositoryDisabler {
        INSTANCE;

        @Override
        public boolean isDisabled(String repositoryId) {
            return false;
        }

        @Override
        public boolean disableRepository(String repositoryId, Throwable throwable) {
            return false;
        }

        @Override
        public Set<String> getDisabledRepositories() {
            return Collections.emptySet();
        }
    }
}
