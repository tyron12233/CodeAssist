package com.tyron.builder.internal.remote.internal.hub;

import com.tyron.builder.internal.remote.internal.hub.protocol.ChannelMessage;
import com.tyron.builder.internal.remote.internal.hub.protocol.EndOfStream;
import com.tyron.builder.internal.remote.internal.hub.queue.MultiEndPointQueue;
import com.tyron.builder.internal.remote.internal.hub.protocol.InterHubMessage;
import com.tyron.builder.internal.remote.internal.hub.protocol.RejectedMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

class OutgoingQueue extends MultiEndPointQueue {
    private final IncomingQueue incomingQueue;

    OutgoingQueue(IncomingQueue incomingQueue, Lock lock) {
        super(lock);
        this.incomingQueue = incomingQueue;
    }

    void endOutput() {
        dispatch(new EndOfStream());
    }

    void discardQueued() {
        List<InterHubMessage> rejected = new ArrayList<InterHubMessage>();
        drain(rejected);
        for (InterHubMessage message : rejected) {
            if (message instanceof ChannelMessage) {
                ChannelMessage channelMessage = (ChannelMessage) message;
                incomingQueue.queue(new RejectedMessage(channelMessage.getChannel(), channelMessage.getPayload()));
            }
        }
    }
}
