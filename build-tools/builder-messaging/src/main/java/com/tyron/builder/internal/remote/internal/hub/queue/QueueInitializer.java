/*
 * Copyright 2016 the original author or authors.
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

package com.tyron.builder.internal.remote.internal.hub.queue;

import com.tyron.builder.internal.dispatch.Dispatch;
import com.tyron.builder.internal.remote.internal.hub.protocol.EndOfStream;
import com.tyron.builder.internal.remote.internal.hub.protocol.InterHubMessage;

public class QueueInitializer {
    private EndOfStream endOfStream;

    void onStatefulMessage(InterHubMessage message) {
        if (message instanceof EndOfStream) {
            endOfStream = (EndOfStream) message;
        } else {
            throw new UnsupportedOperationException(String.format("Received unexpected stateful message: %s", message));
        }
    }

    void onQueueAdded(Dispatch<InterHubMessage> queue) {
        if (endOfStream != null) {
            queue.dispatch(endOfStream);
        }
    }
}
