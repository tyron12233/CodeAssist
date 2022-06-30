package com.tyron.builder.internal.logging.console;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.nativeintegration.console.ConsoleMetaData;

import java.io.Flushable;
import java.io.IOException;


import com.tyron.builder.api.Action;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.nativeintegration.console.ConsoleMetaData;

import java.io.Flushable;
import java.io.IOException;

public class AnsiConsole implements Console {
    private final Action<AnsiContext> redrawAction = new Action<AnsiContext>() {
        @Override
        public void execute(AnsiContext ansiContext) {
            buildStatusArea.redraw(ansiContext);
            // When build output area is not visible, position the cursor at the end of the output area
            if (!buildStatusArea.isVisible()) {
                ansiContext.cursorAt(buildOutputArea.getWritePosition());
            }
        }
    };
    private final Flushable flushable;
    private final MultiLineBuildProgressArea buildStatusArea = new MultiLineBuildProgressArea();
    private final DefaultTextArea buildOutputArea;
    private final AnsiExecutor ansiExecutor;

    public AnsiConsole(Appendable target, Flushable flushable, ColorMap colorMap, ConsoleMetaData consoleMetaData, boolean forceAnsi) {
        this(target, flushable, colorMap, consoleMetaData, new DefaultAnsiFactory(forceAnsi));
    }

    private AnsiConsole(Appendable target, Flushable flushable, ColorMap colorMap, ConsoleMetaData consoleMetaData, AnsiFactory factory) {
        this.flushable = flushable;
        this.ansiExecutor = new DefaultAnsiExecutor(target, colorMap, factory, consoleMetaData, Cursor.newBottomLeft(), new Listener());

        buildOutputArea = new DefaultTextArea(ansiExecutor);
    }

    @Override
    public void flush() {
        redraw();
        try {
            flushable.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void redraw() {
        // Calculate how many rows of the status area overlap with the text area
        int numberOfOverlappedRows = buildStatusArea.getWritePosition().row - buildOutputArea.getWritePosition().row;

        // If textArea is on a status line but nothing was written, this means a new line was just written. While
        // we wait for additional text, we assume this row doesn't count as overlapping and use it as a status
        // line. In the opposite case, we want to scroll the progress area one more line. This avoid having an one
        // line gap between the text area and the status area.
        if (buildOutputArea.getWritePosition().col > 0) {
            numberOfOverlappedRows++;
        }

        if (numberOfOverlappedRows > 0) {
            buildStatusArea.scrollDownBy(numberOfOverlappedRows);
        }

        ansiExecutor.write(redrawAction);
    }

    @Override
    public StyledLabel getStatusBar() {
        return buildStatusArea.getProgressBar();
    }

    @Override
    public BuildProgressArea getBuildProgressArea() {
        return buildStatusArea;
    }

    @Override
    public TextArea getBuildOutputArea() {
        return buildOutputArea;
    }

    private class Listener implements DefaultAnsiExecutor.NewLineListener {
        @Override
        public void beforeNewLineWritten(AnsiContext ansi, Cursor writeCursor) {
            if (buildStatusArea.isOverlappingWith(writeCursor)) {
                ansi.eraseForward();
            }

            if (writeCursor.row == 0) {
                buildOutputArea.newLineAdjustment();
                buildStatusArea.newLineAdjustment();
            }
        }

        @Override
        public void beforeLineWrap(AnsiContext ansi, Cursor writeCursor) {
            if (writeCursor.row == 0) {
                buildStatusArea.newLineAdjustment();
            }
        }

        @Override
        public void afterLineWrap(AnsiContext ansi, Cursor writeCursor) {
            if (buildStatusArea.isOverlappingWith(writeCursor)) {
                ansi.eraseForward();
            }
        }
    }
}
