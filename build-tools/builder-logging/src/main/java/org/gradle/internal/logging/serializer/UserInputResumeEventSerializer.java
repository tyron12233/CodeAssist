package org.gradle.internal.logging.serializer;

import org.gradle.internal.logging.events.UserInputResumeEvent;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

public class UserInputResumeEventSerializer implements Serializer<UserInputResumeEvent> {

    @Override
    public void write(Encoder encoder, UserInputResumeEvent event) throws Exception {
    }

    @Override
    public UserInputResumeEvent read(Decoder decoder) throws Exception {
        return new UserInputResumeEvent();
    }
}
