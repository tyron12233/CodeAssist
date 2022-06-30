package com.tyron.code.ui.wizard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
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
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.github.angads25.filepicker.controller.adapters.FileListAdapter;
import com.github.angads25.filepicker.model.DialogConfigs;
import com.github.angads25.filepicker.model.DialogProperties;
import com.github.angads25.filepicker.model.MarkedItemList;
import com.github.angads25.filepicker.view.FilePickerDialog;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.transition.MaterialFadeThrough;
import com.google.android.material.transition.MaterialSharedAxis;
import com.tyron.builder.project.Project;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.ui.wizard.adapter.WizardTemplateAdapter;
import com.tyron.code.util.UiUtilsKt;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.Decompress;
import com.tyron.common.util.SingleTextWatcher;
import com.tyron.completion.progress.ProgressManager;

import org.apache.commons.io.FileUtils;
import javax.lang.model.SourceVersion;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

@SuppressWarnings("ConstantConditions")
public class WizardFragment extends Fragment {

    public interface OnProjectCreatedListener {
        void onProjectCreated(Project project);
    }

    private Button mNavigateButton;
    private Button mExitButton;
    private RecyclerView mRecyclerView;
    private LinearLayout mLoadingLayout;
    private WizardTemplateAdapter mAdapter;

    private View mWizardTemplatesView;
    private View mWizardDetailsView;

    private boolean mLast;
    private boolean mShowDialogOnPermissionGrant = false;
    private boolean mUseInternalStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;

    private WizardTemplate mCurrentTemplate;

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            onNavigateBack();
        }
    };
    private ActivityResultLauncher<String[]> mPermissionLauncher;
    private final ActivityResultContracts.RequestMultiplePermissions mPermissionsContract =
            new ActivityResultContracts.RequestMultiplePermissions();
    private OnProjectCreatedListener mListener;

    public void setOnProjectCreatedListener(OnProjectCreatedListener listener) {
        mListener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));

        mPermissionLauncher = registerForActivityResult(mPermissionsContract, isGranted -> {
            if (isGranted.containsValue(false)) {
                mUseInternalStorage = true;
                initializeSaveLocation();
            } else {
                mUseInternalStorage = false;
                if (mShowDialogOnPermissionGrant) {
                    mShowDialogOnPermissionGrant = false;
                    showDirectoryPickerDialog();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        onBackPressedCallback.setEnabled(false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), onBackPressedCallback);

        View view = inflater.inflate(R.layout.wizard_fragment, container, false);
        LinearLayout layout = view.findViewById(R.id.setup_wizard_layout);

        View footer = view.findViewById(R.id.footer);
        UiUtilsKt.addSystemWindowInsetToPadding(footer, false, true, false, true);

        mNavigateButton = layout.findViewById(R.id.wizard_next);
        mNavigateButton.setVisibility(View.GONE);
        mNavigateButton.setOnClickListener(this::onNavigateNext);

        mExitButton = layout.findViewById(R.id.exit_button);
        mExitButton.setOnClickListener(v -> onNavigateBack());

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

    private void onNavigateBack() {
        if (!mLast) {
            getParentFragmentManager().popBackStack();
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
        mNameLayout = mWizardDetailsView.findViewById(R.id.til_app_name);
        mNameLayout.getEditText().addTextChangedListener(new SingleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                verifyClassName(editable);
            }
        });
        mPackageNameLayout = mWizardDetailsView.findViewById(R.id.til_package_name);
        mPackageNameLayout.getEditText().addTextChangedListener(new SingleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                verifyPackageName(editable);
            }
        });

        mSaveLocationLayout = mWizardDetailsView.findViewById(R.id.til_save_location);
        mSaveLocationLayout.getEditText()
                .setText(PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getString(SharedPreferenceKeys.PROJECT_SAVE_PATH,
                                requireContext().getExternalFilesDir("Projects")
                                        .getAbsolutePath()));
        initializeSaveLocation();

        mSaveLocationLayout.getEditText().addTextChangedListener(new SingleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                verifySaveLocation(editable);
            }
        });

        mLanguageLayout = mWizardDetailsView.findViewById(R.id.til_language);
        mLanguageText = mWizardDetailsView.findViewById(R.id.et_language);

        mMinSdkLayout = mWizardDetailsView.findViewById(R.id.til_min_sdk);
        mMinSdkText = mWizardDetailsView.findViewById(R.id.et_min_sdk);
        mMinSdkText.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, getSdks()));
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

    private boolean isGrantedStoragePermission() {
        return ContextCompat.checkSelfPermission(requireActivity(),
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(requireActivity(),
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldShowRequestPermissionRationale() {
        return shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void requestPermissions() {
        mPermissionLauncher.launch(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE});
    }

    private void initializeSaveLocation() {
        if (mUseInternalStorage) {
            mSaveLocationLayout.setHelperText(getString(R.string.wizard_scoped_storage_info));
            mSaveLocationLayout.getEditText().setText(requireContext()
                    .getExternalFilesDir("Projects").getAbsolutePath());
            mSaveLocationLayout.getEditText().setInputType(InputType.TYPE_NULL);
        }
//        mSaveLocationLayout.setEndIconOnClickListener(view -> {
//            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
//                if (isGrantedStoragePermission()) {
//                    showDirectoryPickerDialog();
//                } else if (shouldShowRequestPermissionRationale()) {
//                    new MaterialAlertDialogBuilder(view.getContext())
//                            .setMessage("The application needs storage permissions in order to save project files that " +
//                                    "will not be deleted when you uninstall the app. Alternatively you can choose to " +
//                                    "save project files into the app's internal storage.")
//                            .setPositiveButton("Allow", (d, which) -> {
//                                mShowDialogOnPermissionGrant = true;
//                                requestPermissions();
//                            })
//                            .setNegativeButton("Use internal storage", (d, which) -> {
//                                mUseInternalStorage = true;
//                                initializeSaveLocation();
//                            })
//                            .setTitle("Storage permissions")
//                            .show();
//                } else {
//                    mShowDialogOnPermissionGrant = true;
//                    requestPermissions();
//                }
//            }
//        });
    }

    @SuppressLint("SetTextI18n")
    private void showDirectoryPickerDialog() {
        DialogProperties properties = new DialogProperties();
        properties.selection_mode = DialogConfigs.SINGLE_MODE;
        properties.selection_type = DialogConfigs.DIR_SELECT;
        properties.root = Environment.getExternalStorageDirectory();
        properties.error_dir = requireContext().getExternalFilesDir(null);

        FilePickerDialog dialog = new FilePickerDialog(requireContext(), properties);
        dialog.setDialogSelectionListener(files -> {
            String file = files[0];
            mSaveLocationLayout.getEditText()
                    .setText(file);
        });
        dialog.setOnShowListener((d) -> {
            // work around to set the color of the dialog buttons to white since the color
            // accent of the app is orange
            Button cancel = dialog.findViewById(com.github.angads25.filepicker.R.id.cancel);
            Button select = dialog.findViewById(com.github.angads25.filepicker.R.id.select);

            cancel.setTextColor(Color.WHITE);
            select.setTextColor(Color.WHITE);

            String positiveButtonNameStr = getString(com.github.angads25.filepicker.R.string.choose_button_label);
            try {
                Field mAdapterField = dialog.getClass().getDeclaredField("mFileListAdapter");
                mAdapterField.setAccessible(true);
                FileListAdapter adapter = (FileListAdapter) mAdapterField.get(dialog);
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
        });
        dialog.show();
    }

    private boolean validateDetails() {

        requireActivity().runOnUiThread(() -> {
            verifyPackageName(mPackageNameLayout.getEditText().getText());
            verifyClassName(mNameLayout.getEditText().getText());
            verifySaveLocation(mSaveLocationLayout.getEditText().getText());
        });

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

        if (TextUtils.isEmpty(mMinSdkText.getText())) {
            return false;
        }

        return mCurrentTemplate != null;
    }

    private void verifyClassName(Editable editable) {
        String name = editable.toString();
        if (TextUtils.isEmpty(name)) {
            mNameLayout.setError(getString(R.string.wizard_error_name_empty));
            return;
        } else if (name.contains(File.pathSeparator) || name.contains(File.separator)) {
            mNameLayout.setError(getString(R.string.wizard_error_name_illegal));
            return;
        } else {
            mNameLayout.setErrorEnabled(false);
        }

        File file = new File(
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getString(SharedPreferenceKeys.PROJECT_SAVE_PATH,
                                requireContext().getExternalFilesDir("Projects").getAbsolutePath())
                        + "/" + editable.toString());
        String suffix = "";
        if (file.exists()) {
            suffix = "-1";
        }
        String path = file.getAbsolutePath() + suffix.trim();
        mSaveLocationLayout.getEditText().setText(path);
    }

    private void verifySaveLocation(Editable editable) {
        if (editable.toString().length() >= 240) {
            mSaveLocationLayout.setError(getString(R.string.wizard_path_exceeds));
            return;
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

    private void verifyPackageName(Editable editable) {
        String packageName = editable.toString();
        String[] packages = packageName.split("\\.");
        for (String name : packages) {
            if (name.isEmpty() || !SourceVersion.isName(name)) {
                mPackageNameLayout.setError(getString(R.string.wizard_package_illegal));
                return;
            }
        }
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

    private void createProjectAsync() {
        TransitionManager.beginDelayedTransition((ViewGroup) requireView(), new MaterialFadeThrough());
        mWizardTemplatesView.setVisibility(View.GONE);
        mWizardDetailsView.setVisibility(View.GONE);
        mLoadingLayout.setVisibility(View.VISIBLE);

        ProgressManager.getInstance().runNonCancelableAsync(() -> {
            String savePath = mSaveLocationLayout.getEditText().getText().toString();

            try {
                if (validateDetails()) {
                    createProject();
                } else {
                    requireActivity().runOnUiThread(this::showDetailsView);
                    return;
                }

                Project project = new Project(new File(savePath));
                replacePlaceholders(project.getRootFile());

                if (getActivity() != null && mListener != null) {
                    requireActivity().runOnUiThread(() -> {
                        getParentFragmentManager().popBackStack();
                        mListener.onProjectCreated(project);
                    });
                }
            } catch (IOException e) {
                requireActivity().runOnUiThread(() -> {
                    ApplicationLoader.showToast(e.getMessage());
                    showDetailsView();
                });
            }
        });
    }

    /**
     * Traverses all files in a directory, including subdirectory and replaces
     * placeholders with the right text.
     *
     * @param file Root directory to start
     */
    @WorkerThread
    private void replacePlaceholders(File file) throws IOException {
        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                if (child.isDirectory()) {
                    replacePlaceholders(child);
                    continue;
                }
                if (child.getName().endsWith(".gradle")) {
                    replacePlaceholder(child);
                } else if (child.getName().endsWith(".java") || child.getName().endsWith(".kt")) {
                    replacePlaceholder(child);
                } else if (child.getName().endsWith(".xml")) {
                    replacePlaceholder(child);
                }
            }
        }
    }

    /**
     * Replaces the placeholders in a file such as $packagename, $appname
     *
     * @param file Input file
     */
    @WorkerThread
    private void replacePlaceholder(File file) throws IOException {
        String string;
        try {
            string = FileUtils.readFileToString(file, Charset.defaultCharset());
        } catch (IOException e) {
            return;
        }
        String targetSdk = "31";
        String minSdk = mMinSdkText.getText().toString()
                .substring("API".length() + 1, "API".length() + 3); // at least 2 digits
        int minSdkInt = Integer.parseInt(minSdk);

        FileUtils.writeStringToFile(
                file,
                string.replace("$packagename", mPackageNameLayout.getEditText().getText())
                        .replace("$appname", mNameLayout.getEditText().getText())
                        .replace("${targetSdkVersion}", targetSdk)
                        .replace("${minSdkVersion}", String.valueOf(minSdkInt)),
                StandardCharsets.UTF_8
        );
    }

    @WorkerThread
    private void createProject() throws IOException {

        File projectRoot = new File(mSaveLocationLayout.getEditText().getText().toString());
        if (!projectRoot.exists()) {
            if (!projectRoot.mkdirs()) {
                throw new IOException("Unable to create directory");
            }
        }
        boolean isJava = mLanguageText.getText().toString().equals("Java");
        File sourcesDir = new File(mCurrentTemplate.getPath() +
                "/" + (isJava ? "java" : "kotlin"));
        if (!sourcesDir.exists()) {
            throw new IOException("Unable to find source file for language " +
                    mLanguageText.getText());
        }

        String packageNameDir = mPackageNameLayout.getEditText()
                .getText().toString()
                .replace(".", "/");
        File targetSourceDir = new File(projectRoot, "/app/src/main/java/" + packageNameDir);
        if (!targetSourceDir.exists()) {
            if (!targetSourceDir.mkdirs()) {
                throw new IOException("Unable to create target directory");
            }
        }
        FileUtils.copyDirectory(sourcesDir, projectRoot);
        FileUtils.deleteDirectory(new File(projectRoot, "app/src/main/java/$packagename"));
        FileUtils.copyDirectory(new File(sourcesDir,
                "app/src/main/java/$packagename"), targetSourceDir);
    }

    private List<String> getSdks() {
        return Arrays.asList(
                "API 16: Android 4.0 (Ice Cream Sandwich)",
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
        List<String> languages = new ArrayList<>();
        if (mCurrentTemplate != null) {
            if (mCurrentTemplate.isSupportsJava()) {
                languages.add("Java");
            }
            if (mCurrentTemplate.isSupportsKotlin()) {
                languages.add("Kotlin");
            }
        }
        mLanguageText.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, languages));

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
                    TransitionManager.beginDelayedTransition((ViewGroup) requireView(),
                            new MaterialFadeThrough());
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
        try {
            File file = requireContext().getExternalFilesDir("templates");
            extractTemplatesMaybe();

            File[] templateFiles = file.listFiles();
            if (templateFiles == null) {
                return Collections.emptyList();
            }
            if (templateFiles.length == 0) {
                extractTemplatesMaybe();
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
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private void extractTemplatesMaybe() throws IOException {
        File hashFile = new File(requireContext().getExternalFilesDir("templates"), "hash");
        if (!hashFile.exists()) {
            extractTemplates();
        } else {
            InputStream newIs = requireContext().getAssets()
                    .open("templates.zip");
            String newIsMd5 = AndroidUtilities.calculateMD5(newIs);
            String oldMd5 = FileUtils.readFileToString(hashFile, Charset.defaultCharset());

            if (!newIsMd5.equals(oldMd5)) {
                extractTemplates();
            } else {
                Log.d("WizardExtractor", "Templates are up to date");
            }
        }
    }

    private void extractTemplates() throws IOException {
        File templatesDir = new File(requireContext().getExternalFilesDir(null),
                "templates");
        if (templatesDir.exists()) {
            FileUtils.deleteDirectory(templatesDir);
        }

        Decompress.unzipFromAssets(requireContext(), "templates.zip",
                templatesDir.getParent());
        File hashFile = new File(templatesDir, "hash");
        if (!hashFile.createNewFile()) {
            throw new IOException("Unable to create hash file");
        }
        FileUtils.writeStringToFile(hashFile,
                AndroidUtilities.calculateMD5(requireContext().getAssets()
                        .open("templates.zip")),
                Charset.defaultCharset());
    }
}
