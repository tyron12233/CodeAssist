package org.gradle.internal.logging.serializer;

import org.gradle.internal.logging.events.UserInputRequestEvent;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

public class UserInputRequestEventSerializer implements Serializer<UserInputRequestEvent> {

    @Override
    public void write(Encoder encoder, UserInputRequestEvent event) throws Exception {
    }

    @Override
    public UserInputRequestEvent read(Decoder decoder) throws Exception {
        return new UserInputRequestEvent();
    }
}
