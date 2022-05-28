package com.tyron.builder.internal.remote.internal.hub;

import com.tyron.builder.internal.remote.internal.hub.protocol.ChannelMessage;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.ObjectReader;
import com.tyron.builder.internal.serialize.ObjectWriter;
import com.tyron.builder.internal.serialize.StatefulSerializer;
import com.tyron.builder.internal.remote.internal.hub.protocol.ChannelIdentifier;
import com.tyron.builder.internal.remote.internal.hub.protocol.EndOfStream;
import com.tyron.builder.internal.remote.internal.hub.protocol.InterHubMessage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class InterHubMessageSerializer implements StatefulSerializer<InterHubMessage> {
    private static final byte CHANNEL_MESSAGE = 1;
    private static final byte END_STREAM_MESSAGE = 2;
    private final StatefulSerializer<Object> payloadSerializer;

    public InterHubMessageSerializer(StatefulSerializer<Object> payloadSerializer) {
        this.payloadSerializer = payloadSerializer;
    }

    @Override
    public ObjectReader<InterHubMessage> newReader(Decoder decoder) {
        return new MessageReader(decoder, payloadSerializer.newReader(decoder));
    }

    @Override
    public ObjectWriter<InterHubMessage> newWriter(Encoder encoder) {
        return new MessageWriter(encoder, payloadSerializer.newWriter(encoder));
    }

    private static class MessageReader implements ObjectReader<InterHubMessage> {
        private final Map<Integer, ChannelIdentifier> channels = new HashMap<Integer, ChannelIdentifier>();
        private final Decoder decoder;
        private final ObjectReader<?> payloadReader;

        public MessageReader(Decoder decoder, ObjectReader<?> payloadReader) {
            this.decoder = decoder;
            this.payloadReader = payloadReader;
        }

        @Override
        public InterHubMessage read() throws Exception {
            switch (decoder.readByte()) {
                case CHANNEL_MESSAGE:
                    ChannelIdentifier channelId = readChannelId();
                    Object payload = payloadReader.read();
                    return new ChannelMessage(channelId, payload);
                case END_STREAM_MESSAGE:
                    return new EndOfStream();
                default:
                    throw new IllegalArgumentException();
            }
        }

        private ChannelIdentifier readChannelId() throws IOException {
            int channelNum = decoder.readSmallInt();
            ChannelIdentifier channelId = channels.get(channelNum);
            if (channelId == null) {
                String channel = decoder.readString();
                channelId = new ChannelIdentifier(channel);
                channels.put(channelNum, channelId);
            }
            return channelId;
        }
    }

    private static class MessageWriter implements ObjectWriter<InterHubMessage> {
        private final Map<ChannelIdentifier, Integer> channels = new HashMap<ChannelIdentifier, Integer>();
        private final Encoder encoder;
        private final ObjectWriter<Object> payloadWriter;

        public MessageWriter(Encoder encoder, ObjectWriter<Object> payloadWriter) {
            this.encoder = encoder;
            this.payloadWriter = payloadWriter;
        }

        @Override
        public void write(InterHubMessage message) throws Exception {
            if (message instanceof ChannelMessage) {
                ChannelMessage channelMessage = (ChannelMessage) message;
                encoder.writeByte(CHANNEL_MESSAGE);
                writeChannelId(channelMessage);
                payloadWriter.write(channelMessage.getPayload());
            } else if (message instanceof EndOfStream) {
                encoder.writeByte(END_STREAM_MESSAGE);
            } else {
                throw new IllegalArgumentException();
            }
        }

        private void writeChannelId(ChannelMessage channelMessage) throws IOException {
            Integer channelNum = channels.get(channelMessage.getChannel());
            if (channelNum == null) {
                channelNum = channels.size();
                channels.put(channelMessage.getChannel(), channelNum);
                encoder.writeSmallInt(channelNum);
                encoder.writeString(channelMessage.getChannel().getName());
            } else {
                encoder.writeSmallInt(channelNum);
            }
        }
    }
}
