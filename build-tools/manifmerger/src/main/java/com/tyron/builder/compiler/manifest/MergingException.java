package com.tyron.builder.compiler.manifest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.tyron.builder.compiler.manifest.blame.Message;
import com.tyron.builder.compiler.manifest.blame.SourceFile;
import com.tyron.builder.compiler.manifest.blame.SourceFilePosition;
import com.tyron.builder.compiler.manifest.blame.SourcePosition;

import org.xml.sax.SAXParseException;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Exception for errors during merging.
 */
public class MergingException extends Exception {

    public static final String MULTIPLE_ERRORS = "Multiple errors:";

    @NotNull
    private final List<Message> mMessages;

    /**
     * For internal use. Creates a new MergingException
     *
     * @param cause    the original exception. May be null.
     * @param messages the messaged. Must contain at least one item.
     */
    protected MergingException(@Nullable Throwable cause, @NotNull Message... messages) {
        super(messages.length == 1 ? messages[0].getText() : MULTIPLE_ERRORS, cause);
        mMessages = ImmutableList.copyOf(messages);
    }

    public static class Builder {

        @Nullable
        private Throwable mCause = null;

        @Nullable
        private String mMessageText = null;

        @Nullable
        private String mOriginalMessageText = null;

        @NotNull
        private SourceFile mFile = SourceFile.UNKNOWN;

        @NotNull
        private SourcePosition mPosition = SourcePosition.UNKNOWN;

        private Builder() {
        }

        public Builder wrapException(@NotNull Throwable cause) {
            mCause = cause;
            mOriginalMessageText = Throwables.getStackTraceAsString(cause);
            return this;
        }

        public Builder withFile(@NotNull File file) {
            mFile = new SourceFile(file);
            return this;
        }

        public Builder withFile(@NotNull SourceFile file) {
            mFile = file;
            return this;
        }

        public Builder withPosition(@NotNull SourcePosition position) {
            mPosition = position;
            return this;
        }

        public Builder withMessage(@NotNull String messageText, Object... args) {
            mMessageText = args.length == 0 ? messageText : String.format(messageText, args);
            return this;
        }

        public MergingException build() {
            if (mCause != null) {
                if (mMessageText == null) {
                    mMessageText = java.util.Objects.requireNonNull(
                            mCause.getLocalizedMessage(), mCause.getClass().getCanonicalName());
                }
                if (mPosition == SourcePosition.UNKNOWN && mCause instanceof SAXParseException) {
                    SAXParseException exception = (SAXParseException) mCause;
                    int lineNumber = exception.getLineNumber();
                    if (lineNumber != -1) {
                        // Convert positions to be 0-based for SourceFilePosition.
                        mPosition = new SourcePosition(lineNumber - 1,
                                exception.getColumnNumber() - 1, -1);
                    }
                }
            }

            if (mMessageText == null) {
                mMessageText = "Unknown error.";
            }

            return new MergingException(
                    mCause,
                    new Message(
                            Message.Kind.ERROR,
                            mMessageText,
                            Objects.requireNonNull(mOriginalMessageText, mMessageText),
                            new SourceFilePosition(mFile, mPosition)));
        }

    }

    public static Builder wrapException(@NotNull Throwable cause) {
        return new Builder().wrapException(cause);
    }

    public static Builder withMessage(@NotNull String message, Object... args) {
        return new Builder().withMessage(message, args);
    }


    public static void throwIfNonEmpty(Collection<Message> messages) throws MergingException {
        if (!messages.isEmpty()) {
            throw new MergingException(null, Iterables.toArray(messages, Message.class));
        }
    }

    @NotNull
    public List<Message> getMessages() {
        return mMessages;
    }

    /**
     * Computes the error message to display for this error
     */
    @NotNull
    @Override
    public String getMessage() {
        List<String> messages = Lists.newArrayListWithCapacity(mMessages.size());
        for (Message message : mMessages) {
            StringBuilder sb = new StringBuilder();
            List<SourceFilePosition> sourceFilePositions = message.getSourceFilePositions();
            if (sourceFilePositions.size() > 1 || !sourceFilePositions.get(0)
                    .equals(SourceFilePosition.UNKNOWN)) {
                sb.append(Joiner.on('\t').join(sourceFilePositions));
            }

            String text = message.getText();
            if (sb.length() > 0) {
                sb.append(':').append(' ');

                // ALWAYS insert the string "Error:" between the path and the message.
                // This is done to make the error messages more simple to detect
                // (since a generic path: message pattern can match a lot of output, basically
                // any labeled output, and we don't want to do file existence checks on any random
                // string to the left of a colon.)
                if (!text.startsWith("Error: ")) {
                    sb.append("Error: ");
                }
            } else if (!text.contains("Error: ")) {
                sb.append("Error: ");
            }

            // If the error message already starts with the path, strip it out.
            // This avoids redundant looking error messages you can end up with
            // like for example for permission denied errors where the error message
            // string itself contains the path as a prefix:
            //    /my/full/path: /my/full/path (Permission denied)
            if (sourceFilePositions.size() == 1) {
                File file = sourceFilePositions.get(0).getFile().getSourceFile();
                if (file != null) {
                    String path = file.getAbsolutePath();
                    if (text.startsWith(path)) {
                        int stripStart = path.length();
                        if (text.length() > stripStart && text.charAt(stripStart) == ':') {
                            stripStart++;
                        }
                        if (text.length() > stripStart && text.charAt(stripStart) == ' ') {
                            stripStart++;
                        }
                        text = text.substring(stripStart);
                    }
                }
            }

            sb.append(text);
            messages.add(sb.toString());
        }
        return Joiner.on('\n').join(messages);
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
