package com.tyron.builder.internal.logging.serializer;

import com.tyron.builder.internal.logging.events.UserInputResumeEvent;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

public class UserInputResumeEventSerializer implements Serializer<UserInputResumeEvent> {

    @Override
    public void write(Encoder encoder, UserInputResumeEvent event) throws Exception {
    }

    @Override
    public UserInputResumeEvent read(Decoder decoder) throws Exception {
        return new UserInputResumeEvent();
    }
}
