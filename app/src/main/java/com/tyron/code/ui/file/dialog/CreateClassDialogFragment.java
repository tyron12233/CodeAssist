package com.tyron.code.ui.file.dialog;

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
import com.tyron.code.template.java.JavaClassTemplate;
import com.tyron.code.ui.file.RegexReason;
import com.tyron.common.util.SingleTextWatcher;

import javax.lang.model.SourceVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CreateClassDialogFragment extends DialogFragment {

    public static CreateClassDialogFragment newInstance(List<CodeTemplate> templates, List<RegexReason> regex) {
        CreateClassDialogFragment fragment = new CreateClassDialogFragment();
        Bundle args = new Bundle();
        args.putParcelableArrayList("regex", new ArrayList<>(regex));
        args.putParcelableArrayList("templates", new ArrayList<>(templates));
        fragment.setArguments(args);
        return fragment;
    }

    public interface OnClassCreatedListener {
        void onClassCreated(String className, CodeTemplate template);
    }

    private List<CodeTemplate> mTemplates;
    private OnClassCreatedListener mListener;

    private TextInputLayout mClassNameLayout;
    private TextInputEditText mClassNameEditText;
    private AppCompatAutoCompleteTextView mClassTypeTextView;

    public void setOnClassCreatedListener(OnClassCreatedListener listener) {
        mListener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTemplates = getTemplates();
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
        mClassTypeTextView = view.findViewById(R.id.et_class_type);
        mClassTypeTextView.setAdapter(adapter);
        mClassTypeTextView.setText(mTemplates.get(0).getName(), false);

        builder.setView(view);
        builder.setPositiveButton(R.string.create_class_dialog_positive, ((dialogInterface, i) -> {
            if (mListener != null) {
                mListener.onClassCreated(String.valueOf(mClassNameEditText.getText()), getCurrentTemplate());
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
                    if (getCurrentTemplate() instanceof JavaClassTemplate) {
                        if (SourceVersion.isName(name)) {
                            mClassNameLayout.setErrorEnabled(false);
                            positiveButton.setEnabled(true);
                        } else {
                            positiveButton.setEnabled(false);
                            mClassNameLayout.setError(getString(R.string.create_class_dialog_invalid_name));
                        }
                    } else {
                        if (isValid(name)) {
                            mClassNameLayout.setErrorEnabled(false);
                            positiveButton.setEnabled(true);
                        } else {
                            positiveButton.setEnabled(false);
                        }
                    }
                }
            });
        });
        return dialog;
    }


    private CodeTemplate getCurrentTemplate() {
        String name = String.valueOf(mClassTypeTextView.getText());
        return mTemplates.stream()
                .filter(temp -> temp.getName().equals(name))
                .findAny()
                .orElse(new JavaClassTemplate());
    }
    private List<CodeTemplate> getTemplates() {
        return requireArguments().getParcelableArrayList("templates");
    }

    private boolean isValid(String string) {
        List<RegexReason> regex = requireArguments().getParcelableArrayList("regex");
        for (RegexReason s : regex) {
            Pattern pattern = Pattern.compile(s.getRegexString());
            Matcher matcher = pattern.matcher(string);
            if (!matcher.find()) {
                mClassNameLayout.setError(s.getReason());
                return false;
            }
        }
        return true;
    }
}
