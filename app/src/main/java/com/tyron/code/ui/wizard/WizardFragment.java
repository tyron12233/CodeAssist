package com.tyron.code.ui.wizard;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.transition.MaterialFadeThrough;
import com.google.android.material.transition.MaterialSharedAxis;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.wizard.adapter.WizardTemplateAdapter;
import com.tyron.code.util.AndroidUtilities;
import com.tyron.common.util.Decompress;
import com.tyron.code.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

@SuppressWarnings("ConstantConditions")
public class WizardFragment extends Fragment {

    private Button mNavigateButton;
    private Button mExitButton;
    private RecyclerView mRecyclerView;
    private LinearLayout mLoadingLayout;
    private WizardTemplateAdapter mAdapter;

    private View mWizardTemplatesView;
    private View mWizardDetailsView;

    private boolean mLast;

    private ActivityResultLauncher<Uri> mLocationLauncher;

    private WizardTemplate mCurrentTemplate;

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            onNavigateBack(mExitButton);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));

        mLocationLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                result -> {
                    if (result != null) {
                        try {
                            mSaveLocationLayout.getEditText()
                                    .setText(FileUtils.getPath(result)
                                    .replace("%20", " "));
                        } catch (Exception e) {
                            ApplicationLoader.showToast(e.getMessage());
                        }
                    }
                }
        );
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(this, onBackPressedCallback);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.wizard_fragment, container, false);
        LinearLayout layout = view.findViewById(R.id.setup_wizard_layout);

        mNavigateButton = layout.findViewById(R.id.wizard_next);
        mNavigateButton.setVisibility(View.GONE);
        mNavigateButton.setOnClickListener(this::onNavigateNext);

        mExitButton = layout.findViewById(R.id.exit_button);
        mExitButton.setOnClickListener(this::onNavigateBack);

        mRecyclerView = layout.findViewById(R.id.template_recyclerview);
        mRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(),
                AndroidUtilities.getRowCount(AndroidUtilities.dp(132))));

        mLoadingLayout = layout.findViewById(R.id.loading_layout);
        mWizardTemplatesView = layout.findViewById(R.id.wizard_templates_layout);
        mWizardDetailsView = layout.findViewById(R.id.wizard_details_layout);

        mAdapter = new WizardTemplateAdapter();
        mRecyclerView.setAdapter(mAdapter);

        initDetailsView();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        loadTemplates();
    }

    private void onNavigateBack(View view) {
        if (!mLast) {
            getParentFragmentManager().beginTransaction()
                    .remove(this)
                    .commit();
        } else {
            showTemplatesView();
            mLast = false;
        }
    }


    private void onNavigateNext(View view) {
        if (!mLast) {
            showDetailsView();
            mLast = true;
        } else {
            createProjectAsync();
        }
    }

    private void showTemplatesView() {
        mWizardTemplatesView.setVisibility(View.GONE);

        MaterialSharedAxis sharedAxis = new MaterialSharedAxis(MaterialSharedAxis.X, false);

        TransitionManager.beginDelayedTransition((ViewGroup) requireView(), sharedAxis);

        mWizardDetailsView.setVisibility(View.GONE);
        mWizardTemplatesView.setVisibility(View.VISIBLE);
        mNavigateButton.setVisibility(View.GONE);
        mNavigateButton.setText(R.string.wizard_next);
        mExitButton.setText(R.string.wizard_exit);
    }

    private TextInputLayout mNameLayout;
    private TextInputLayout mSaveLocationLayout;
    private TextInputLayout mPackageNameLayout;
    private TextInputLayout mMinSdkLayout;
    private TextInputLayout mLanguageLayout;

    private AutoCompleteTextView mLanguageText;
    private AutoCompleteTextView mMinSdkText;

    private void initDetailsView() {
        List<String> languages = Collections.singletonList("Java");

        mNameLayout = mWizardDetailsView.findViewById(R.id.til_app_name);
        mNameLayout.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (TextUtils.isEmpty(mNameLayout.getEditText().getText())) {
                    mNameLayout.setError(getString(R.string.wizard_error_name_empty));
                } else {
                    mNameLayout.setErrorEnabled(false);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    File file = new File(requireContext().getExternalFilesDir(null) + "/" + "Projects" + "/" + editable.toString());
                    String suffix = "";
                    if (file.exists()) {
                        suffix = "-1";
                    }
                    String path = file.getAbsolutePath() + suffix.trim();
                    mSaveLocationLayout.getEditText().setText(path);
                }
            }
        });
        mPackageNameLayout = mWizardDetailsView.findViewById(R.id.til_package_name);
        mPackageNameLayout.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                String packageName = editable.toString();
                String[] packages = packageName.split("\\.");
                if (packages == null) {
                    mPackageNameLayout.setError(getString(R.string.wizard_package_empty));
                } else if (packages.length == 1) {
                    mPackageNameLayout.setError(getString(R.string.wizard_package_too_short));
                } else if (packageName.endsWith(".")) {
                    mPackageNameLayout.setError(getString(R.string.wizard_package_illegal));
                } else if (packageName.contains(" ")) {
                    mPackageNameLayout.setError(getString(R.string.wizard_package_contains_spaces));
                } else if (!packageName.matches("^[a-zA-Z0-9.]+$")) {
                    mPackageNameLayout.setError(getString(R.string.wizard_package_illegal));
                } else {
                    mPackageNameLayout.setErrorEnabled(false);
                }
            }
        });
        mSaveLocationLayout = mWizardDetailsView.findViewById(R.id.til_save_location);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mSaveLocationLayout.setHelperText(getString(R.string.wizard_scoped_storage_info));
            mSaveLocationLayout.getEditText().setText(requireContext().getExternalFilesDir("Projects").getAbsolutePath());
            mSaveLocationLayout.getEditText().setInputType(InputType.TYPE_NULL);
        } else {
            mSaveLocationLayout.setEndIconOnClickListener(view -> mLocationLauncher.launch(null));
        }
        mSaveLocationLayout.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (editable.toString().length() >= 240) {
                    mSaveLocationLayout.setError(getString(R.string.wizard_path_exceeds));
                } else {
                    mSaveLocationLayout.setErrorEnabled(false);
                }
                File file = new File(editable.toString());
                if (file.getParentFile() == null || !file.getParentFile().canWrite()) {
                    mSaveLocationLayout.setError(getString(R.string.wizard_file_not_writable));
                } else {
                    mSaveLocationLayout.setErrorEnabled(false);
                }
            }
        });

        mLanguageLayout = mWizardDetailsView.findViewById(R.id.til_language);
        mLanguageText = mWizardDetailsView.findViewById(R.id.et_language);
        mLanguageText.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, languages));

        mMinSdkLayout = mWizardDetailsView.findViewById(R.id.til_min_sdk);
        mMinSdkText = mWizardDetailsView.findViewById(R.id.et_min_sdk);
        mMinSdkText.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, getSdks()));
        mMinSdkText.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                mMinSdkLayout.setErrorEnabled(false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                mMinSdkLayout.setError(getString(R.string.wizard_select_min_sdk));
            }
        });
    }

    private void createProjectAsync() {
        TransitionManager.beginDelayedTransition((ViewGroup) requireView(), new MaterialFadeThrough());
        mWizardTemplatesView.setVisibility(View.GONE);
        mWizardDetailsView.setVisibility(View.GONE);
        mLoadingLayout.setVisibility(View.VISIBLE);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (validateDetails()) {
                    createProject();
                } else {
                    requireActivity().runOnUiThread(this::showDetailsView);
                    return;
                }
            } catch (IOException e) {
                requireActivity().runOnUiThread(() -> {
                    ApplicationLoader.showToast(e.getMessage());
                    showDetailsView();
                });
                return;
            }

            Project project = new Project(new File(mSaveLocationLayout.getEditText().getText().toString()));
            replacePlaceholders(project.mRoot);

            requireActivity().runOnUiThread(() -> {
                Fragment fragment = getParentFragmentManager().findFragmentByTag("main_fragment");
                if (fragment instanceof MainFragment) {
                    ((MainFragment) fragment).openProject(project, true);
                }

                getParentFragmentManager().beginTransaction()
                        .remove(WizardFragment.this)
                        .commit();
            });
        });
    }

    /**
     * Traverses all files in a directory, including subdirectory and replaces
     * placeholders with the right text.
     * @param file Root directory to start
     */
    private void replacePlaceholders(File file) {
        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                if (child.isDirectory()) {
                    replacePlaceholders(child);
                    continue;
                }
                if (child.getName().equals("build.gradle")) {
                    replacePlaceholder(child);
                } else if (child.getName().endsWith(".java")) {
                    replacePlaceholder(child);
                } else if (child.getName().endsWith(".xml")) {
                    replacePlaceholder(child);
                }
            }
        }
    }

    /**
     * Replaces the placeholders in a file such as $packagename, $appname
     * @param file Input file
     */
    private void replacePlaceholder(File file) {
        String string;
        try {
            string = org.apache.commons.io.FileUtils.readFileToString(file, Charset.defaultCharset());
        } catch (IOException e) {
            return;
        }
        String targetSdk = "31";
        String minSdk = mMinSdkText.getText().toString()
                .substring("API".length() + 1, "API".length() + 3); // at least 2 digits
        int minSdkInt = Integer.parseInt(minSdk);

        FileManager.writeFile(
                file,
                string.replace("$packagename", mPackageNameLayout.getEditText().getText())
                .replace("$appname", mNameLayout.getEditText().getText())
                .replace("${targetSdkVersion}", targetSdk)
                .replace("${minSdkVersion}", String.valueOf(minSdkInt))
        );
    }

    private void createProject() throws IOException  {
        if (!validateDetails()) {
            return;
        }

        File projectRoot = new File(mSaveLocationLayout.getEditText().getText().toString());
        if (!projectRoot.exists()) {
            if (!projectRoot.mkdirs()) {
                throw new IOException("Unable to create directory");
            }
        }
        boolean isJava = mLanguageText.getText().toString().equals("Java");
        File sourcesDir = new File(mCurrentTemplate.getPath() + "/" + (isJava ? "java" : "kotlin"));
        if (!sourcesDir.exists()) {
            throw new IOException("Unable to find source file for language " + mLanguageText.getText());
        }

        String packageNameDir = mPackageNameLayout.getEditText().getText().toString().replace(".", "/");
        File targetSourceDir = new File(projectRoot, "/app/src/main/java/" + packageNameDir);
        if (!targetSourceDir.exists()) {
            if (!targetSourceDir.mkdirs()) {
                throw new IOException("Unable to create target directory");
            }
        }
        org.apache.commons.io.FileUtils.copyDirectory(new File(sourcesDir, "$packagename"), targetSourceDir);
        org.apache.commons.io.FileUtils.copyDirectory(new File(sourcesDir.getParentFile(), "files"), projectRoot);
    }

    private boolean validateDetails() {

        if (mPackageNameLayout.isErrorEnabled()) {
            return false;
        }

        if (mSaveLocationLayout.isErrorEnabled()) {
            return false;
        }

        if (mPackageNameLayout.isErrorEnabled()) {
            return false;
        }

        if (mMinSdkLayout.isErrorEnabled()) {
            return false;
        }

        if (TextUtils.isEmpty(mNameLayout.getEditText().getText().toString())) {
            mNameLayout.post(() -> mNameLayout.setError(getString(R.string.wizard_error_name_empty)));
            return false;
        }

        if (TextUtils.isEmpty(mSaveLocationLayout.getEditText().getText())) {
            mSaveLocationLayout.post(() -> mSaveLocationLayout.setError(getString(R.string.wizard_select_save_location)));
            return false;
        }

        if (TextUtils.isEmpty(mMinSdkText.getText())) {
            return false;
        }

        return mCurrentTemplate != null;
    }

    private List<String> getSdks() {
        return Arrays.asList(
                "API 16: Android 4.0 (JellyBean)",
                "API 17: Android 4.2 (JellyBean)",
                "API 18: Android 4.3 (JellyBean)",
                "API 19: Android 4.4 (KitKat)",
                "API 20: Android 4.4W (KitKat Wear)",
                "API 21: Android 5.0 (Lollipop)",
                "API 22: Android 5.1 (Lollipop)",
                "API 23: Android 6.0 (Marshmallow)",
                "API 24: Android 7.0 (Nougat)",
                "API 25: Android 7.1 (Nougat)",
                "API 26: Android 8.0 (Oreo)",
                "API 27: Android 8.1 (Oreo)",
                "API 28: Android 9.0 (Pie)",
                "API 29: Android 10.0 (Q)",
                "API 30: Android 11.0 (R)",
                "API 31: Android 12.0 (S)"
        );
    }

    private void showDetailsView() {
        mLoadingLayout.setVisibility(View.GONE);
        mWizardDetailsView.setVisibility(View.GONE);

        MaterialSharedAxis sharedAxis = new MaterialSharedAxis(MaterialSharedAxis.X, true);

        TransitionManager.beginDelayedTransition((ViewGroup) requireView(), sharedAxis);

        mWizardDetailsView.setVisibility(View.VISIBLE);
        mWizardTemplatesView.setVisibility(View.GONE);
        mNavigateButton.setText(R.string.wizard_create);
        mNavigateButton.setVisibility(View.VISIBLE);
        mExitButton.setText(R.string.wizard_previous);
    }

    private void loadTemplates() {
        TransitionManager.beginDelayedTransition((ViewGroup) requireView(), new MaterialFadeThrough());
        mLoadingLayout.setVisibility(View.VISIBLE);
        mRecyclerView.setVisibility(View.GONE);

        Executors.newSingleThreadExecutor().execute(() -> {
            List<WizardTemplate> templates = getTemplates();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    TransitionManager.beginDelayedTransition((ViewGroup) requireView(), new MaterialFadeThrough());
                    mLoadingLayout.setVisibility(View.GONE);
                    mRecyclerView.setVisibility(View.VISIBLE);

                    mAdapter.submitList(templates);


                    mAdapter.setOnItemClickListener((item, pos) -> {
                        mCurrentTemplate = item;
                        onNavigateNext(mNavigateButton);
                    });
                });
            }
        });
    }

    private List<WizardTemplate> getTemplates() {
        File file = requireContext().getExternalFilesDir("templates");
        if (!file.exists()) {
            extractTemplates();
        }

        File[] templateFiles = file.listFiles();
        if (templateFiles == null) {
            return Collections.emptyList();
        }
        if (templateFiles.length == 0) {
            extractTemplates();
        }
        templateFiles = file.listFiles();
        if (templateFiles == null) {
            return Collections.emptyList();
        }

        List<WizardTemplate> templates = new ArrayList<>();
        for (File child : templateFiles) {
            WizardTemplate template = WizardTemplate.fromFile(child);
            if (template != null) {
                templates.add(template);
            }
        }
        return templates;
    }

    private void extractTemplates() {
        Decompress.unzipFromAssets(requireContext(),
                "templates.zip",
                requireContext().getExternalFilesDir(null).getAbsolutePath());
    }
}
