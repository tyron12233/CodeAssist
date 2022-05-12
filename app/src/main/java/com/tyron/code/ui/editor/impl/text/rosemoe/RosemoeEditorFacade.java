package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.core.content.res.ResourcesCompat;

import com.tyron.code.R;
import com.tyron.code.language.LanguageManager;
import com.tyron.code.ui.editor.CodeAssistCompletionAdapter;
import com.tyron.editor.Content;

import org.apache.commons.vfs2.FileObject;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora2.text.EditorUtil;

public class RosemoeEditorFacade {

    private final Content content;
    private final FrameLayout container;
    private final CodeEditorView editor;

    RosemoeEditorFacade(Context context, Content content, FileObject file) {
        this.content = content;

        container = new FrameLayout(context);

        editor = new CodeEditorView(context);
        configureEditor(editor, file);
        container.addView(editor);
    }

    private void configureEditor(CodeEditorView editor, FileObject file) {
        Language language = LanguageManager.getInstance().get(editor, file);
        if (language == null) {
            language = new EmptyLanguage();
        }
        editor.setEditorLanguage(language);

        // only used for checking the extension
        editor.openFile(file.getPath().toFile());
        Bundle bundle = new Bundle();
        bundle.putBoolean("loaded", true);
        bundle.putBoolean("bg", true);
        editor.setText(content, bundle);
        editor.setTypefaceText(ResourcesCompat.getFont(editor.getContext(), R.font.jetbrains_mono_regular));

        editor.setAutoCompletionItemAdapter(new CodeAssistCompletionAdapter());
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            | EditorInfo.TYPE_CLASS_TEXT
                            | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                            | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    }

    public View getView() {
        return container;
    }

    public Content getContent() {
        return content;
    }
}
