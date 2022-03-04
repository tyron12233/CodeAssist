package com.tyron.code.language;

import androidx.annotation.NonNull;

/**
 * Marker interface for languages that support formatting
 */
public interface EditorFormatter {

    /**
     * Formats the given CharSequence on the specified start and end indices.
     * @param text The text to format.
     * @param startIndex The 0-based index of where the format starts
     * @param endIndex The 0-based index of where the format ends
     * @return The formatted text
     */
    @NonNull
    CharSequence format(@NonNull CharSequence text, int startIndex, int endIndex);
}
