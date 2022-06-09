package com.tyron.builder.internal.remote.internal.inet;

import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MultiChoiceAddressSerializer implements Serializer<MultiChoiceAddress> {
    @Override
    public MultiChoiceAddress read(Decoder decoder) throws IOException {
        UUID canonicalAddress = new UUID(decoder.readLong(), decoder.readLong());
        int port = decoder.readInt();
        int addressCount = decoder.readSmallInt();
        List<InetAddress> addresses = new ArrayList<InetAddress>(addressCount);
        for (int i = 0; i < addressCount; i++) {
            InetAddress address = InetAddress.getByAddress(decoder.readBinary());
            addresses.add(address);
        }
        return new MultiChoiceAddress(canonicalAddress, port, addresses);
    }

    @Override
    public void write(Encoder encoder, MultiChoiceAddress address) throws IOException {
        UUID canonicalAddress = address.getCanonicalAddress();
        encoder.writeLong(canonicalAddress.getMostSignificantBits());
        encoder.writeLong(canonicalAddress.getLeastSignificantBits());
        encoder.writeInt(address.getPort());
        encoder.writeSmallInt(address.getCandidates().size());
        for (InetAddress inetAddress : address.getCandidates()) {
            encoder.writeBinary(inetAddress.getAddress());
        }
    }
}
