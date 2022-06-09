package com.tyron.builder.internal.logging.serializer;

import com.tyron.builder.internal.logging.events.ProgressStartEvent;
import com.tyron.builder.internal.operations.BuildOperationCategory;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;

/**
 * Since Gradle creates a high volume of progress events, this serializer trades simplicity
 * for the smallest possible serialized form. It uses a single byte indicating the presence of optional
 * fields instead of writing an "absent" byte for each of them like most of our serializers do.
 * It also encodes the {@link BuildOperationCategory} in this byte, since that enum only has 3 values
 * for the forseeable future.
 */
public class ProgressStartEventSerializer implements Serializer<ProgressStartEvent> {
    private static final short PARENT_PROGRESS_ID = 1;
    private static final short LOGGING_HEADER = 1 << 2;
    private static final short LOGGING_HEADER_IS_SUB_DESCRIPTION = 1 << 3;
    private static final short STATUS = 1 << 4;
    private static final short STATUS_IS_SUB_DESCRIPTION = 1 << 5;
    private static final short BUILD_OPERATION_ID = 1 << 6;
    private static final short BUILD_OPERATION_ID_IS_PROGRESS_ID = 1 << 7;
    private static final short BUILD_OPERATION_START = 1 << 8;
    private static final short CATEGORY_IS_TASK = 1 << 9;
    private static final short CATEGORY_IS_BUILD_OP = 1 << 10;
    private static final short CATEGORY_NAME = 1 << 11;
    private static final short BUILD_OP_CATEGORY_OFFSET = 12;
    private static final short BUILD_OP_CATEGORY_MASK = 0x7;

    public ProgressStartEventSerializer() {
        BuildOperationCategory maxCategory = BuildOperationCategory.values()[BuildOperationCategory.values().length - 1];
        if ((BUILD_OP_CATEGORY_MASK & maxCategory.ordinal()) != maxCategory.ordinal()) {
            // Too many build operation categories to fit into the flags assigned to encode the category - so you will need to adjust the mask above
            throw new IllegalArgumentException("Too many categories to fit into flags.");
        }
    }

    @Override
    public void write(Encoder encoder, ProgressStartEvent event) throws Exception {
        int flags = 0;
        OperationIdentifier parentProgressOperationId = event.getParentProgressOperationId();
        if (parentProgressOperationId != null) {
            flags |= PARENT_PROGRESS_ID;
        }

        String description = event.getDescription();

        String loggingHeader = event.getLoggingHeader();
        if (loggingHeader != null) {
            if (description.endsWith(loggingHeader)) {
                flags |= LOGGING_HEADER_IS_SUB_DESCRIPTION;
            } else {
                flags |= LOGGING_HEADER;
            }
        }

        String status = event.getStatus();
        if (!status.isEmpty()) {
            if (description.endsWith(status)) {
                flags |= STATUS_IS_SUB_DESCRIPTION;
            } else {
                flags |= STATUS;
            }
        }

        OperationIdentifier buildOperationId = event.getBuildOperationId();
        if (buildOperationId != null) {
            if (buildOperationId.equals(event.getProgressOperationId())) {
                flags |= BUILD_OPERATION_ID_IS_PROGRESS_ID;
            } else {
                flags |= BUILD_OPERATION_ID;
            }
        }

        BuildOperationCategory buildOperationCategory = event.getBuildOperationCategory();
        flags |= (buildOperationCategory.ordinal() & BUILD_OP_CATEGORY_MASK) << BUILD_OP_CATEGORY_OFFSET;

        if (event.isBuildOperationStart()) {
            flags |= BUILD_OPERATION_START;
        }

        if (event.getCategory().equals(ProgressStartEvent.BUILD_OP_CATEGORY)) {
            flags |= CATEGORY_IS_BUILD_OP;
        } else if (event.getCategory().equals(ProgressStartEvent.TASK_CATEGORY)) {
            flags |= CATEGORY_IS_TASK;
        } else {
            flags |= CATEGORY_NAME;
        }

        encoder.writeSmallInt(flags);

        encoder.writeSmallLong(event.getProgressOperationId().getId());
        if (parentProgressOperationId != null) {
            encoder.writeSmallLong(parentProgressOperationId.getId());
        }
        encoder.writeLong(event.getTimestamp());
        if ((flags & CATEGORY_NAME) != 0) {
            encoder.writeString(event.getCategory());
        }
        encoder.writeString(description);
        if ((flags & LOGGING_HEADER) != 0) {
            encoder.writeString(loggingHeader);
        } else if ((flags & LOGGING_HEADER_IS_SUB_DESCRIPTION) != 0) {
            encoder.writeSmallInt(loggingHeader.length());
        }
        if ((flags & STATUS) != 0) {
            encoder.writeString(status);
        } else if ((flags & STATUS_IS_SUB_DESCRIPTION) != 0) {
            encoder.writeSmallInt(status.length());
        }
        encoder.writeSmallInt(event.getTotalProgress());

        if ((flags & BUILD_OPERATION_ID) != 0) {
            encoder.writeSmallLong(buildOperationId.getId());
        }
    }

    @Override
    public ProgressStartEvent read(Decoder decoder) throws Exception {
        long flags = decoder.readSmallInt();
        OperationIdentifier progressOperationId = new OperationIdentifier(decoder.readSmallLong());

        OperationIdentifier parentProgressOperationId = null;
        if ((flags & PARENT_PROGRESS_ID) != 0) {
            parentProgressOperationId = new OperationIdentifier(decoder.readSmallLong());
        }

        long timestamp = decoder.readLong();

        String category;
        if ((flags & CATEGORY_IS_TASK) != 0) {
            category = ProgressStartEvent.TASK_CATEGORY;
        } else if ((flags & CATEGORY_IS_BUILD_OP) != 0) {
            category = ProgressStartEvent.BUILD_OP_CATEGORY;
        } else {
            category = decoder.readString();
        }

        String description = decoder.readString();

        String loggingHeader = null;
        if ((flags & LOGGING_HEADER) != 0) {
            loggingHeader = decoder.readString();
        } else if ((flags & LOGGING_HEADER_IS_SUB_DESCRIPTION) != 0) {
            int length = decoder.readSmallInt();
            loggingHeader = description.substring(description.length() - length);
        }

        String status = "";
        if ((flags & STATUS) != 0) {
            status = decoder.readString();
        } else if ((flags & STATUS_IS_SUB_DESCRIPTION) != 0) {
            int length = decoder.readSmallInt();
            status = description.substring(description.length() - length);
        }

        int totalProgress = decoder.readSmallInt();

        boolean buildOperationStart = (flags & BUILD_OPERATION_START) != 0;

        OperationIdentifier buildOperationId = null;
        if ((flags & BUILD_OPERATION_ID) != 0) {
            buildOperationId = new OperationIdentifier(decoder.readSmallLong());
        } else if ((flags & BUILD_OPERATION_ID_IS_PROGRESS_ID) != 0) {
            buildOperationId = progressOperationId;
        }

        BuildOperationCategory buildOperationCategory = BuildOperationCategory.values()[(int) ((flags >> BUILD_OP_CATEGORY_OFFSET) & BUILD_OP_CATEGORY_MASK)];

        return new ProgressStartEvent(
            progressOperationId,
            parentProgressOperationId,
            timestamp,
            category,
            description,
            loggingHeader,
            status,
            totalProgress,
            buildOperationStart,
            buildOperationId,
            buildOperationCategory
        );
    }
}
