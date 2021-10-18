package com.tyron.code.ui.file.action;

import android.content.Context;
import android.view.Menu;

import java.io.File;

public abstract class FileAction {

    public abstract boolean isApplicable(File file);

    public abstract void addMenu(ActionContext context);
}
