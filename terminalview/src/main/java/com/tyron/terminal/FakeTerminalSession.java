package com.tyron.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class FakeTerminalSession extends TerminalOutput {


    @Override
    public void write(byte[] data, int offset, int count) {

    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {

    }

    @Override
    public void onCopyTextToClipboard(String text) {

    }

    @Override
    public void onPasteTextFromClipboard() {

    }

    @Override
    public void onBell() {

    }

    @Override
    public void onColorsChanged() {

    }
}
