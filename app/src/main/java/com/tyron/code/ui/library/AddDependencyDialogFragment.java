package com.tyron.code.ui.library;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.code.R;
import com.tyron.common.util.SingleTextWatcher;

public class AddDependencyDialogFragment extends DialogFragment {

    public static final String TAG = AddDependencyDialogFragment.class.getSimpleName();
    public static final String ADD_KEY = "addDependency";

    @SuppressWarnings("ConstantConditions")
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        // noinspection InflateParams
        View inflate = getLayoutInflater().inflate(R.layout.add_dependency_dialog, null);
        EditText groupId = inflate.findViewById(R.id.et_group_id);
        EditText artifactId = inflate.findViewById(R.id.et_artifact_id);
        EditText versionName = inflate.findViewById(R.id.et_version_name);

        builder.setView(inflate);

        builder.setPositiveButton(R.string.wizard_create, (d, w) -> {
            Bundle bundle = new Bundle();
            bundle.putString("groupId", String.valueOf(groupId.getText()));
            bundle.putString("artifactId", String.valueOf(artifactId.getText()));
            bundle.putString("versionName", String.valueOf(versionName.getText()));
            getParentFragmentManager().setFragmentResult(ADD_KEY, bundle);
        });
        builder.setNegativeButton(android.R.string.cancel, null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            final Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setEnabled(false);

            SingleTextWatcher textWatcher = new SingleTextWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    boolean valid = validate(groupId, artifactId, versionName);
                    positiveButton.setEnabled(valid);
                }
            };
            groupId.addTextChangedListener(textWatcher);
            artifactId.addTextChangedListener(textWatcher);
            versionName.addTextChangedListener(textWatcher);
        });
        return dialog;
    }

    private boolean validate(EditText groupId, EditText artifactId, EditText versionName) {
        String groupIdString = String.valueOf(groupId.getText());
        String artifactIdString = String.valueOf(artifactId.getText());
        String versionNameString = String.valueOf(versionName.getText());
        if (groupIdString.contains(":")) {
            return false;
        }
        if (groupIdString.isEmpty()) {
            return false;
        }
        if (artifactIdString.isEmpty()) {
            return false;
        }
        if (artifactIdString.contains(":")) {
            return false;
        }
        if (versionNameString.isEmpty()) {
            return false;
        }
        return !versionNameString.contains(":");
    }
}
