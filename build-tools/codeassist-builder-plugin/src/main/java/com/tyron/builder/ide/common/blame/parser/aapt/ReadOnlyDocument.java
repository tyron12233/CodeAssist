package com.tyron.builder.ide.common.blame.parser.aapt;

import com.android.annotations.NonNull;
import com.android.ide.common.blame.SourcePosition;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A read-only representation of the text of a file.
 */
class ReadOnlyDocument {

    @NonNull
    private final String mFileContents;

    @NonNull
    private final List<Integer> myOffsets;

    private File myFile;

    private long myLastModified;

    /**
     * Creates a new {@link ReadOnlyDocument} for the given file.
     *
     * @param file the file whose text will be stored in the document. UTF-8 charset is used to
     *             decode the contents of the file.
     * @throws java.io.IOException if an error occurs while reading the file.
     */
    @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
    ReadOnlyDocument(@NonNull File file) throws IOException {
        String xml = Files.toString(file, Charsets.UTF_8);
        if (xml.startsWith("\uFEFF")) { // Strip byte order mark if necessary
            xml = xml.substring(1);
        }
        mFileContents = xml;
        myFile = file;
        myLastModified = file.lastModified();
        myOffsets = Lists.newArrayListWithExpectedSize(mFileContents.length() / 30);
        for (int i = 0; i < mFileContents.length(); i++) {
            char c = mFileContents.charAt(i);
            if (c == '\n') {
                myOffsets.add(i + 1);
            }
        }
    }

    /**
     * Returns true if the document contents are stale (e.g. no longer current)
     */
    public boolean isStale() {
        long now = myFile.lastModified();
        return now == 0L || myLastModified < now;
    }

    /**
     * Returns the [0-based] offset of the given [0-based] line number,
     * relative to the beginning of the document.
     *
     * @param lineNumber the given line number.
     * @return the offset of the given line. -1 is returned if the document is empty, or if the
     * given line number is negative or greater than the number of lines in the document.
     */
    int lineOffset(int lineNumber) {
        int index = lineNumber - 1;
        if (index < 0 || index >= myOffsets.size()) {
            return -1;
        }
        return myOffsets.get(index);
    }

    /**
     * Returns the [0-based] line number of the given [0-based] offset.
     *
     * @param offset the given offset.
     * @return the line number of the given offset. -1 is returned if the document is empty or if
     * the offset is greater than the position of the last character in the document.
     */
    int lineNumber(int offset) {
        for (int i = 0; i < myOffsets.size(); i++) {
            if (offset < myOffsets.get(i)) {
                return i;
            }
        }
        return -1;
    }

    SourcePosition sourcePosition(int offset) {
        for (int i = 0; i < myOffsets.size(); i++) {
            if (offset < myOffsets.get(i)) {
                int lineStartOffset = i==0 ? 0 : myOffsets.get(i-1);
                return new SourcePosition(i, offset - lineStartOffset, offset);
            }
        }
        return SourcePosition.UNKNOWN;
    }

    /**
     * Finds the given text in the document, starting from the given offset.
     *
     * @param needle   the text to find.
     * @param offset the starting point of the search.
     * @return the offset of the found result, or -1 if no match was found.
     */
    int findText(@NonNull String needle, int offset) {
        Preconditions.checkPositionIndex(offset, mFileContents.length());
        return mFileContents.indexOf(needle, offset);
    }

    int findTextBackwards(String needle, int offset) {
        Preconditions.checkPositionIndex(offset, mFileContents.length());
        return mFileContents.lastIndexOf(needle, offset);
    }

    /**
     * Returns the character at the given offset.
     *
     * @param offset the position, relative to the beginning of the document, of the character to
     *               return.
     * @return the character at the given offset.
     * @throws IndexOutOfBoundsException if the {@code offset} argument is negative or not less than
     *                                   the document's size.
     */
    char charAt(int offset) {
        return mFileContents.charAt(offset);
    }

    /**
     * Returns the sub sequence for the given range.
     *
     * @param start the starting offset.
     * @param end   the ending offset, or -1 for the end of the file.
     * @return the sub sequence.
     */
    String subsequence(int start, int end) {
        return mFileContents.substring(start, end == -1 ? mFileContents.length() : end);
    }

    /**
     * Returns the contents of the document
     *
     * @return the contents
     */
    String getContents() {
        return mFileContents;
    }

    /**
     * @return the size (or length) of the document.
     */
    int length() {
        return mFileContents.length();
    }
}