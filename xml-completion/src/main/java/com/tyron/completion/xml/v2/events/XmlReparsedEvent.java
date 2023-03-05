package com.tyron.completion.xml.v2.events;

import com.tyron.code.event.Event;

import java.io.File;

public class XmlReparsedEvent extends Event {

    private final File file;

    public XmlReparsedEvent(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }
}
