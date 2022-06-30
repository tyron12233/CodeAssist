package com.tyron.builder.internal.remote.internal.inet;

import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

public class SocketInetAddress implements InetEndpoint {
    public static final com.tyron.builder.internal.serialize.Serializer<SocketInetAddress> SERIALIZER = new Serializer();

    private final InetAddress address;
    private final int port;

    public SocketInetAddress(InetAddress address, int port) {
        this.address = address;
        this.port = port;
    }

    @Override
    public String getDisplayName() {
        return address + ":" + port;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o == null || o.getClass() != getClass()) {
            return false;
        }
        SocketInetAddress other = (SocketInetAddress) o;
        return other.address.equals(address) && other.port == port;
    }

    @Override
    public int hashCode() {
        return address.hashCode() ^ port;
    }

    @Override
    public List<InetAddress> getCandidates() {
        return Collections.singletonList(address);
    }

    public InetAddress getAddress() {
        return address;
    }

    @Override
    public int getPort() {
        return port;
    }

    private static class Serializer implements com.tyron.builder.internal.serialize.Serializer<SocketInetAddress> {

        @Override
        public SocketInetAddress read(Decoder decoder) throws Exception {
            return new SocketInetAddress(readAddress(decoder), decoder.readInt());
        }

        private InetAddress readAddress(Decoder decoder) throws IOException {
            return InetAddress.getByAddress(decoder.readBinary());
        }

        @Override
        public void write(Encoder encoder, SocketInetAddress address) throws Exception {
            writeAddress(encoder, address);
            encoder.writeInt(address.port);
        }

        private void writeAddress(Encoder encoder, SocketInetAddress address) throws IOException {
            encoder.writeBinary(address.address.getAddress());
        }
    }
}