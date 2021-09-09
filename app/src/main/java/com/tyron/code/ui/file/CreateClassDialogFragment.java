package com.tyron.code.ui.file;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tyron.code.R;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.template.java.AbstractTemplate;
import com.tyron.code.template.java.InterfaceTemplate;
import com.tyron.code.template.java.JavaClassTemplate;
import com.tyron.code.util.SingleTextWatcher;

import org.openjdk.javax.lang.model.SourceVersion;

import java.util.Arrays;
import java.util.List;

public class CreateClassDialogFragment extends DialogFragment {

    public interface OnClassCreatedListener {
        void onClassCreated(String className, CodeTemplate template);
    }

    private final List<CodeTemplate> mTemplates = getTemplates();
    private OnClassCreatedListener mListener;

    private TextInputLayout mClassNameLayout;
    private TextInputEditText mClassNameEditText;

    public void setOnClassCreatedListener(OnClassCreatedListener listener) {
        mListener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.dialog_create_class_title);

        // noinspection InflateParams
        View view = getLayoutInflater().inflate(R.layout.create_class_dialog, null);

        mClassNameLayout = view.findViewById(R.id.til_class_name);
        mClassNameEditText = view.findViewById(R.id.et_class_name);

        ArrayAdapter<CodeTemplate> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_1, mTemplates);
        AppCompatAutoCompleteTextView classTypeTextView = view.findViewById(R.id.et_class_type);
        classTypeTextView.setAdapter(adapter);
        classTypeTextView.setText(mTemplates.get(0).getName(), false);

        builder.setView(view);
        builder.setPositiveButton(R.string.create_class_dialog_positive, ((dialogInterface, i) -> {
            if (mListener != null) {

                String name = String.valueOf(mClassNameEditText.getText());
                CodeTemplate template = mTemplates.stream()
                        .filter(temp -> temp.getName().equals(name))
                        .findAny()
                        .orElse(new JavaClassTemplate());

                mListener.onClassCreated(String.valueOf(mClassNameEditText.getText()), template);
            }
        }));
        builder.setNegativeButton(android.R.string.cancel, null);

        AlertDialog dialog =  builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            final Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setEnabled(false);

            mClassNameEditText.addTextChangedListener(new SingleTextWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    String name = editable.toString();
                    if (SourceVersion.isName(name)) {
                        mClassNameLayout.setErrorEnabled(false);
                        positiveButton.setEnabled(true);
                    } else {
                        positiveButton.setEnabled(false);
                        mClassNameLayout.setError(getString(R.string.create_class_dialog_invalid_name));
                    }
                }
            });
        });
        return dialog;
    }


    private List<CodeTemplate> getTemplates() {
        return Arrays.asList(
                new JavaClassTemplate(),
                new AbstractTemplate(),
                new InterfaceTemplate());
    }
}