package com.tyron.builder.dexing;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.errors.DesugarDiagnostic;
import com.android.tools.r8.origin.ArchiveEntryOrigin;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.origin.PathOrigin;
import com.android.tools.r8.position.Position;
import com.android.tools.r8.position.TextPosition;
import com.android.tools.r8.position.TextRange;
import com.android.ide.common.blame.Message;
import com.android.ide.common.blame.MessageReceiver;
import com.android.ide.common.blame.SourceFilePosition;
import com.android.ide.common.blame.SourcePosition;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class D8DiagnosticsHandler implements DiagnosticsHandler {
    private final MessageReceiver messageReceiver;
    private final String toolTag;
    private final Set<String> pendingHints = new HashSet<>();

    public D8DiagnosticsHandler(MessageReceiver messageReceiver) {
        this(messageReceiver, "D8");
    }

    public D8DiagnosticsHandler(MessageReceiver messageReceiver, String toolTag) {
        this.messageReceiver = messageReceiver;
        this.toolTag = toolTag;
    }

    public static Origin getOrigin(ClassFileEntry entry) {
        Path root = entry.getInput().getPath();
        if (Files.isRegularFile(root)) {
            return new ArchiveEntryOrigin(entry.getRelativePath(), new PathOrigin(root));
        } else {
            return new PathOrigin(root.resolve(entry.getRelativePath()));
        }
    }

    public static Origin getOrigin(DexArchiveEntry entry) {
        Path root = entry.getDexArchive().getRootPath();
        if (Files.isRegularFile(root)) {
            return new ArchiveEntryOrigin(entry.getRelativePathInArchive(), new PathOrigin(root));
        } else {
            return new PathOrigin(root.resolve(entry.getRelativePathInArchive()));
        }
    }

    @Override
    public void error(Diagnostic warning) {
        messageReceiver.receiveMessage(convertToMessage(Message.Kind.ERROR, warning));
    }

    @Override
    public void warning(Diagnostic warning) {
        messageReceiver.receiveMessage(convertToMessage(Message.Kind.WARNING, warning));
    }

    @Override
    public void info(Diagnostic info) {
        messageReceiver.receiveMessage(convertToMessage(Message.Kind.INFO, info));
    }

    @Override
    public DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel level, Diagnostic diagnostic) {
        if (diagnostic instanceof DesugarDiagnostic) {
            return DiagnosticsLevel.INFO;
        }
//        if (diagnostic instanceof UnsupportedFeatureDiagnostic) {
//            return DiagnosticsLevel.ERROR;
//        }
        return level;
    }

    public Set<String> getPendingHints() {
        return pendingHints;
    }

    protected void addHint(String hint) {
        synchronized (pendingHints) {
            pendingHints.add(hint);
        }
    }

    protected Message convertToMessage(Message.Kind kind, Diagnostic diagnostic) {
        String textMessage = diagnostic.getDiagnosticMessage();
        Origin origin = diagnostic.getOrigin();
        Position positionInOrigin = diagnostic.getPosition();
        return convertToMessage(kind, textMessage, origin, positionInOrigin);
    }

    protected Message convertToMessage(
            Message.Kind kind, String textMessage, Origin origin, Position positionInOrigin) {
        SourceFilePosition position;
        if (origin instanceof PathOrigin) {
            File originFile = ((PathOrigin) origin).getPath().toFile();
            TextPosition startTextPosition;
            TextPosition endTextPosition;
            if (positionInOrigin instanceof TextRange) {
                TextRange textRange = (TextRange) positionInOrigin;
                startTextPosition = textRange.getStart();
                endTextPosition = textRange.getEnd();
            } else if (positionInOrigin instanceof TextPosition) {
                startTextPosition = (TextPosition) positionInOrigin;
                endTextPosition = startTextPosition;
            } else {
                startTextPosition = null;
                endTextPosition = null;
            }
            if (startTextPosition != null) {
                int startLine = startTextPosition.getLine();
                if (startLine != -1) {
                    startLine--;
                }
                int startColumn = startTextPosition.getColumn();
                if (startColumn != -1) {
                    startColumn--;
                }
                int endLine = endTextPosition.getLine();
                if (endLine != -1) {
                    endLine--;
                }
                int endColumn = endTextPosition.getColumn();
                if (endColumn != -1) {
                    endColumn--;
                }

                position =
                        new SourceFilePosition(
                                originFile,
                                new SourcePosition(
                                        startLine,
                                        startColumn,
                                        toIntOffset(startTextPosition.getOffset()),
                                        endLine,
                                        endColumn,
                                        toIntOffset(endTextPosition.getOffset())));

            } else {
                position = new SourceFilePosition(originFile, SourcePosition.UNKNOWN);
            }
        } else if (origin.parent() instanceof PathOrigin) {
            File originFile = ((PathOrigin) origin.parent()).getPath().toFile();
            position = new SourceFilePosition(originFile, SourcePosition.UNKNOWN);
        } else {
            position = SourceFilePosition.UNKNOWN;
            if (origin != Origin.unknown()) {
                textMessage = origin.toString() + ": " + textMessage;
            }
        }

        return new Message(kind, textMessage, textMessage, toolTag, position);
    }

    private static int toIntOffset(long offset) {
        if (offset >= 0 && offset <= Integer.MAX_VALUE) {
            return (int) offset;
        } else {
            return -1;
        }
    }
}