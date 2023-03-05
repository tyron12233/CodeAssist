package com.tyron.completion.xml.v2.events;

import com.tyron.code.event.Event;
import com.tyron.editor.Content;

import java.io.File;

public class XmlResourceChangeEvent extends Event {

    private final File file;
    private final Content newContent;

    public XmlResourceChangeEvent(File file, Content newContent) {
        this.file = file;
        this.newContent = newContent;
    }

    public Content getNewContent() {
        return newContent;
    }

    public File getFile() {
        return file;
    }
}
