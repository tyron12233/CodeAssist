package com.tyron.builder.internal.logging.serializer;

import com.tyron.builder.internal.logging.events.UserInputRequestEvent;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

public class UserInputRequestEventSerializer implements Serializer<UserInputRequestEvent> {

    @Override
    public void write(Encoder encoder, UserInputRequestEvent event) throws Exception {
    }

    @Override
    public UserInputRequestEvent read(Decoder decoder) throws Exception {
        return new UserInputRequestEvent();
    }
}
