package com.tyron.builder.internal.execution.history.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.caching.internal.origin.OriginMetadata;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.serialize.AbstractSerializer;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshot;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshotSerializer;
import com.tyron.builder.internal.snapshot.impl.SnapshotSerializer;

import java.time.Duration;
import java.util.Map;

public class DefaultPreviousExecutionStateSerializer extends AbstractSerializer<PreviousExecutionState> {
    private final Serializer<FileCollectionFingerprint> fileCollectionFingerprintSerializer;
    private final Serializer<FileSystemSnapshot> fileSystemSnapshotSerializer;
    private final Serializer<ImplementationSnapshot> implementationSnapshotSerializer;
    private final Serializer<ValueSnapshot> valueSnapshotSerializer = new SnapshotSerializer();

    public DefaultPreviousExecutionStateSerializer(
            Serializer<FileCollectionFingerprint> fileCollectionFingerprintSerializer,
            Serializer<FileSystemSnapshot> fileSystemSnapshotSerializer
    ) {
        this.fileCollectionFingerprintSerializer = fileCollectionFingerprintSerializer;
        this.fileSystemSnapshotSerializer = fileSystemSnapshotSerializer;
        this.implementationSnapshotSerializer = new ImplementationSnapshotSerializer();
    }

    @Override
    public PreviousExecutionState read(Decoder decoder) throws Exception {
        OriginMetadata originMetadata = new OriginMetadata(
                decoder.readString(),
                Duration.ofMillis(decoder.readLong())
        );

        ImplementationSnapshot taskImplementation = implementationSnapshotSerializer.read(decoder);

        // We can't use an immutable list here because some hashes can be null
        int taskActionsCount = decoder.readSmallInt();
        ImmutableList.Builder<ImplementationSnapshot> taskActionImplementationsBuilder = ImmutableList.builder();
        for (int j = 0; j < taskActionsCount; j++) {
            ImplementationSnapshot actionImpl = implementationSnapshotSerializer.read(decoder);
            taskActionImplementationsBuilder.add(actionImpl);
        }
        ImmutableList<ImplementationSnapshot> taskActionImplementations = taskActionImplementationsBuilder.build();

        ImmutableSortedMap<String, ValueSnapshot> inputProperties = readInputProperties(decoder);
        ImmutableSortedMap<String, FileCollectionFingerprint> inputFilesFingerprints = readFingerprints(decoder);
        ImmutableSortedMap<String, FileSystemSnapshot> outputFilesSnapshots = readSnapshots(decoder);

        boolean successful = decoder.readBoolean();

        return new DefaultPreviousExecutionState(
                originMetadata,
                taskImplementation,
                taskActionImplementations,
                inputProperties,
                inputFilesFingerprints,
                outputFilesSnapshots,
                successful
        );
    }

    @Override
    public void write(Encoder encoder, PreviousExecutionState execution) throws Exception {
        OriginMetadata originMetadata = execution.getOriginMetadata();
        encoder.writeString(originMetadata.getBuildInvocationId());
        encoder.writeLong(originMetadata.getExecutionTime().toMillis());

        implementationSnapshotSerializer.write(encoder, execution.getImplementation());
        ImmutableList<ImplementationSnapshot> additionalImplementations = execution.getAdditionalImplementations();
        encoder.writeSmallInt(additionalImplementations.size());
        for (ImplementationSnapshot actionImpl : additionalImplementations) {
            implementationSnapshotSerializer.write(encoder, actionImpl);
        }

        writeInputProperties(encoder, execution.getInputProperties());
        writeFingerprints(encoder, execution.getInputFileProperties());
        writeSnapshots(encoder, execution.getOutputFilesProducedByWork());

        encoder.writeBoolean(execution.isSuccessful());
    }

    public ImmutableSortedMap<String, ValueSnapshot> readInputProperties(Decoder decoder) throws Exception {
        int size = decoder.readSmallInt();
        if (size == 0) {
            return ImmutableSortedMap.of();
        }
        if (size == 1) {
            return ImmutableSortedMap.of(decoder.readString(), readValueSnapshot(decoder));
        }

        ImmutableSortedMap.Builder<String, ValueSnapshot> builder = ImmutableSortedMap.naturalOrder();
        for (int i = 0; i < size; i++) {
            builder.put(decoder.readString(), readValueSnapshot(decoder));
        }
        return builder.build();
    }

    public void writeInputProperties(Encoder encoder, ImmutableMap<String, ValueSnapshot> properties) throws Exception {
        encoder.writeSmallInt(properties.size());
        for (Map.Entry<String, ValueSnapshot> entry : properties.entrySet()) {
            encoder.writeString(entry.getKey());
            writeValueSnapshot(encoder, entry.getValue());
        }
    }

    private ImmutableSortedMap<String, FileCollectionFingerprint> readFingerprints(Decoder decoder) throws Exception {
        int count = decoder.readSmallInt();
        ImmutableSortedMap.Builder<String, FileCollectionFingerprint> builder = ImmutableSortedMap.naturalOrder();
        for (int fingerprintIdx = 0; fingerprintIdx < count; fingerprintIdx++) {
            String property = decoder.readString();
            FileCollectionFingerprint fingerprint = fileCollectionFingerprintSerializer.read(decoder);
            builder.put(property, fingerprint);
        }
        return builder.build();
    }

    private void writeFingerprints(Encoder encoder, Map<String, FileCollectionFingerprint> fingerprints) throws Exception {
        encoder.writeSmallInt(fingerprints.size());
        for (Map.Entry<String, FileCollectionFingerprint> entry : fingerprints.entrySet()) {
            encoder.writeString(entry.getKey());
            fileCollectionFingerprintSerializer.write(encoder, entry.getValue());
        }
    }

    private ImmutableSortedMap<String, FileSystemSnapshot> readSnapshots(Decoder decoder) throws Exception {
        int count = decoder.readSmallInt();
        ImmutableSortedMap.Builder<String, FileSystemSnapshot> builder = ImmutableSortedMap.naturalOrder();
        for (int snapshotIdx = 0; snapshotIdx < count; snapshotIdx++) {
            String property = decoder.readString();
            FileSystemSnapshot snapshot = fileSystemSnapshotSerializer.read(decoder);
            builder.put(property, snapshot);
        }
        return builder.build();
    }

    private void writeSnapshots(Encoder encoder, ImmutableSortedMap<String, FileSystemSnapshot> snapshots) throws Exception {
        encoder.writeSmallInt(snapshots.size());
        for (Map.Entry<String, FileSystemSnapshot> entry : snapshots.entrySet()) {
            encoder.writeString(entry.getKey());
            fileSystemSnapshotSerializer.write(encoder, entry.getValue());
        }
    }

    private ValueSnapshot readValueSnapshot(Decoder decoder) throws Exception {
        return valueSnapshotSerializer.read(decoder);
    }

    private void writeValueSnapshot(Encoder encoder, ValueSnapshot snapshot) throws Exception {
        valueSnapshotSerializer.write(encoder, snapshot);
    }

}
