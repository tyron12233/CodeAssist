package com.tyron.editor.event;

import com.tyron.editor.Editor;
import com.tyron.editor.EditorFactory;

import org.jetbrains.annotations.NotNull;

import java.util.EventObject;

public class EditorFactoryEvent extends EventObject {
  private final Editor myEditor;

  public EditorFactoryEvent(@NotNull EditorFactory editorFactory, @NotNull Editor editor) {
    super(editorFactory);
    myEditor = editor;
  }

  @NotNull
  public EditorFactory getFactory(){
    return (EditorFactory) getSource();
  }

  @NotNull
  public Editor getEditor() {
    return myEditor;
  }
}