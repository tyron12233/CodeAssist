package com.tyron.terminal;

public class TerminalSessionClientAdapter implements TerminalSessionClient {

    @Override
    public void onTextChanged(TerminalSession changedSession) {

    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {

    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {

    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {

    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {

    }

    @Override
    public void onBell(TerminalSession session) {

    }

    @Override
    public void onColorsChanged(TerminalSession session) {

    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {

    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override
    public void logError(String tag, String message) {

    }

    @Override
    public void logWarn(String tag, String message) {

    }

    @Override
    public void logInfo(String tag, String message) {

    }

    @Override
    public void logDebug(String tag, String message) {

    }

    @Override
    public void logVerbose(String tag, String message) {

    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {

    }

    @Override
    public void logStackTrace(String tag, Exception e) {

    }
}
