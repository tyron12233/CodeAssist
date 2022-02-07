package com.tyron.builder.project.listener;

import java.io.File;

public interface FileListener {

    void onSnapshotChanged(File file, CharSequence contents);
}
