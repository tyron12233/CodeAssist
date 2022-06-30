/*
 * Copyright 2013 the original author or authors.
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

package com.tyron.builder.platform.base.internal;

import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DefaultPolymorphicDomainObjectContainer;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.platform.base.Platform;
import com.tyron.builder.platform.base.PlatformContainer;

public class DefaultPlatformContainer extends DefaultPolymorphicDomainObjectContainer<Platform> implements PlatformContainer {

    public DefaultPlatformContainer(Instantiator instantiator, CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
        super(Platform.class, instantiator, instantiator, collectionCallbackActionDecorator);
    }

}
