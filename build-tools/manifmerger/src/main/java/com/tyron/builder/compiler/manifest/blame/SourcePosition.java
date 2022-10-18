package com.tyron.builder.compiler.manifest.blame;

import org.jetbrains.annotations.NotNull;

import com.google.common.base.Objects;
import java.io.Serializable;

import com.google.errorprone.annotations.Immutable;

/**
 * An immutable position in a text file, used in errors to point the user to an issue.
 *
 * <p>Positions that are unknown are represented by -1.
 */
@Immutable
public final class SourcePosition implements Serializable {

    @NotNull
    public static final SourcePosition UNKNOWN = new SourcePosition();

    private final int mStartLine, mStartColumn, mStartOffset, mEndLine, mEndColumn, mEndOffset;

    public SourcePosition(int startLine, int startColumn, int startOffset,
                          int endLine, int endColumn, int endOffset) {
        mStartLine = startLine;
        mStartColumn = startColumn;
        mStartOffset = startOffset;
        mEndLine = endLine;
        mEndColumn = endColumn;
        mEndOffset = endOffset;
    }

    public SourcePosition(int lineNumber, int column, int offset) {
        mStartLine = mEndLine = lineNumber;
        mStartColumn = mEndColumn = column;
        mStartOffset = mEndOffset = offset;
    }

    private SourcePosition() {
        mStartLine = mStartColumn = mStartOffset = mEndLine = mEndColumn = mEndOffset = -1;
    }

    protected SourcePosition(SourcePosition copy) {
        mStartLine = copy.getStartLine();
        mStartColumn = copy.getStartColumn();
        mStartOffset = copy.getStartOffset();
        mEndLine = copy.getEndLine();
        mEndColumn = copy.getEndColumn();
        mEndOffset = copy.getEndOffset();
    }

    /**
     * Outputs positions as human-readable formatted strings.
     *
     * e.g.
     * <pre>84
     * 84-86
     * 84:5
     * 84:5-28
     * 85:5-86:47</pre>
     *
     * @return a human readable position.
     */
    @Override
    public String toString() {
        if (mStartLine == -1) {
            return "?";
        }
        StringBuilder sB = new StringBuilder(15);
        sB.append(mStartLine + 1); // Humans think that the first line is line 1.
        if (mStartColumn != -1) {
            sB.append(':');
            sB.append(mStartColumn + 1);
        }
        if (mEndLine != -1) {

            if (mEndLine == mStartLine) {
                if (mEndColumn != -1 && mEndColumn != mStartColumn) {
                    sB.append('-');
                    sB.append(mEndColumn + 1);
                }
            } else {
                sB.append('-');
                sB.append(mEndLine + 1);
                if (mEndColumn != -1) {
                    sB.append(':');
                    sB.append(mEndColumn + 1);
                } else if (mStartColumn != -1) {
                    // to distinguish between this case and the case when the start line is the same
                    // as the end line.
                    sB.append(":?");
                }
            }
        }
        return sB.toString();
    }

    private static int parseString(String string) {
        if (string.equals("?")) {
            return 0;
        }
        return Integer.parseInt(string);
    }

    /** Given the human-readable formatted string, returns a source position object. */
    public static SourcePosition fromString(String string) {
        if (string.equals("?")) {
            return UNKNOWN;
        }
        int startLine, endLine = 0, startColumn = 0, endColumn = 0;
        if (string.contains("-")) {
            String[] startAndEndPositions = string.split("-");
            if (startAndEndPositions[0].contains(":")) {
                String[] startPosition = startAndEndPositions[0].split(":");
                startLine = parseString(startPosition[0]);
                startColumn = parseString(startPosition[1]);
            } else {
                startLine = parseString(startAndEndPositions[0]);
            }

            if (startAndEndPositions[1].contains(":")) {
                String[] endPosition = startAndEndPositions[1].split(":");
                endLine = parseString(endPosition[0]);
                endColumn = parseString(endPosition[1]);
            } else {
                if (startColumn != 0) {
                    endLine = startLine;
                    endColumn = parseString(startAndEndPositions[1]);
                } else {
                    endLine = parseString(startAndEndPositions[1]);
                }
            }
        } else {
            if (string.contains(":")) {
                String[] pos = string.split(":");
                startLine = parseString(pos[0]);
                startColumn = parseString(pos[1]);
            } else {
                startLine = parseString(string);
            }
        }

        return new SourcePosition(
                startLine - 1, startColumn - 1, -1, endLine - 1, endColumn - 1, -1);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SourcePosition)) {
            return false;
        }
        SourcePosition other = (SourcePosition) obj;

        return other.mStartLine == mStartLine &&
                other.mStartColumn == mStartColumn &&
                other.mStartOffset == mStartOffset &&
                other.mEndLine == mEndLine &&
                other.mEndColumn == mEndColumn &&
                other.mEndOffset == mEndOffset;
    }

    @Override
    public int hashCode() {
        return Objects
                .hashCode(mStartLine, mStartColumn, mStartOffset, mEndLine, mEndColumn, mEndOffset);
    }

    public int getStartLine() {
        return mStartLine;
    }

    public int getStartColumn() {
        return mStartColumn;
    }

    public int getStartOffset() {
        return mStartOffset;
    }


    public int getEndLine() {
        return mEndLine;
    }

    public int getEndColumn() {
        return mEndColumn;
    }

    public int getEndOffset() {
        return mEndOffset;
    }

    /**
     * Compares the start of this SourcePosition with another.
     * @return 0 if they are the same, &lt; 0 if this &lt; other and &gt; 0 if this &gt; other
     */
    public int compareStart(@NotNull SourcePosition other) {
        if (mStartOffset != -1 && other.mStartOffset != -1) {
            return mStartOffset - other.mStartOffset;
        }
        if (mStartLine == other.mStartLine && mStartColumn != -1 && other.mStartColumn != -1) {
            return mStartColumn - other.mStartColumn;
        }
        return mStartLine - other.mStartLine;
    }

    /**
     * Compares the end of this SourcePosition with another.
     * @return 0 if they are the same, &lt; 0 if this &lt; other and &gt; 0 if this &gt; other
     */
    public int compareEnd(@NotNull SourcePosition other) {
        if (mEndOffset != -1 && other.mEndOffset != -1) {
            return mEndOffset - other.mEndOffset;
        }
        if (mEndLine == other.mEndLine && mEndColumn != -1 && other.mEndColumn != -1) {
            return mEndColumn - other.mEndColumn;
        }
        return mEndLine - other.mEndLine;
    }
}