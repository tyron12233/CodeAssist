/*
 * Copyright 2010 the original author or authors.
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

package com.tyron.builder.api.internal.tasks.testing;

import com.tyron.builder.api.tasks.testing.TestOutputEvent;
import com.tyron.builder.internal.scan.UsedByScanPlugin;

@UsedByScanPlugin("test-distribution")
public class DefaultTestOutputEvent implements TestOutputEvent {

    private final Destination destination;
    private final String message;

    @UsedByScanPlugin("test-distribution")
    public DefaultTestOutputEvent(Destination destination, String message) {
        this.destination = destination;
        this.message = message;
    }

    @Override
    public Destination getDestination() {
        return destination;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultTestOutputEvent that = (DefaultTestOutputEvent) o;

        if (destination != that.destination) {
            return false;
        }
        if (!message.equals(that.message)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = destination.hashCode();
        result = 31 * result + message.hashCode();
        return result;
    }
}
