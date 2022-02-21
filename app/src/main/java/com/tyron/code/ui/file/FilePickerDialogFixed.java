package com.tyron.code.ui.file;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import com.github.angads25.filepicker.controller.adapters.FileListAdapter;
import com.github.angads25.filepicker.model.DialogProperties;
import com.github.angads25.filepicker.model.MarkedItemList;
import com.github.angads25.filepicker.view.FilePickerDialog;

import java.lang.reflect.Field;

public class FilePickerDialogFixed extends FilePickerDialog {

    public FilePickerDialogFixed(Context context) {
        super(context);
    }

    public FilePickerDialogFixed(Context context, DialogProperties properties) {
        super(context, properties);
    }

    public FilePickerDialogFixed(Context context, DialogProperties properties, int themeResId) {
        super(context, properties, themeResId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }


    @Override
    protected void onStart() {
        super.onStart();

        Button cancel = findViewById(com.github.angads25.filepicker.R.id.cancel);
        Button select = findViewById(com.github.angads25.filepicker.R.id.select);
        String positiveButtonNameStr = getContext().getString(com.github.angads25.filepicker.R.string.choose_button_label);

        cancel.setTextColor(Color.WHITE);
        select.setTextColor(Color.WHITE);

        try {
            Field mAdapterField = FilePickerDialog.class.getDeclaredField("mFileListAdapter");
            mAdapterField.setAccessible(true);
            FileListAdapter adapter = (FileListAdapter) mAdapterField.get(this);
            assert adapter != null;
            adapter.setNotifyItemCheckedListener(() -> {
                int size = MarkedItemList.getFileCount();
                if (size == 0) {
                    select.setEnabled(false);
                    select.setTextColor(Color.WHITE);
                    select.setText(positiveButtonNameStr);
                } else {
                    select.setEnabled(true);
                    select.setText(positiveButtonNameStr + " (" + size + ") ");
                }
                adapter.notifyDataSetChanged();
            });
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.w("WizardFragment", "Unable to get declared field", e);
        }
    }

    /**
     * Return the current path of the current directory
     * @return the absolute path of the directory
     */
    public String getCurrentPath() {
        TextView path = findViewById(com.github.angads25.filepicker.R.id.dir_path);
        return String.valueOf(path.getText());
    }
}
