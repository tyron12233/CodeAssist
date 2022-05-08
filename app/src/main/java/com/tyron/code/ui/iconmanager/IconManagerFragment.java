package com.tyron.code.ui.iconmanager;

import android.app.ProgressDialog;

import android.os.Bundle;

import android.view.ViewGroup;

import android.view.LayoutInflater;

import android.view.View;

import android.widget.TextView;

import android.widget.Toast;

import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.fragment.app.Fragment;

import androidx.recyclerview.widget.GridLayoutManager;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.android.material.transition.MaterialSharedAxis;

import com.tyron.code.R;

import com.tyron.code.util.UiUtilsKt;

import com.tyron.code.ui.project.ProjectManager;

import com.tyron.completion.progress.ProgressManager;

import com.tyron.common.util.Decompress;

import com.tyron.code.ui.iconmanager.adapter.IconAdapter;

import java.io.File;

import java.io.FileNotFoundException;

import java.io.FileOutputStream;

import java.io.IOException;

import java.io.OutputStream;

import java.util.ArrayList;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Node;

import org.w3c.dom.NodeList;

import org.w3c.dom.Element;

public class IconManagerFragment extends Fragment {

	public static String TAG = IconManagerFragment.class.getSimpleName();

        private String iconFolderDirectory, projectResourceDirectory;

	private ArrayList<String> iconList = new ArrayList<>();

	private ProgressDialog pDialog;

	@Override

	public void onCreate(@Nullable Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));

		setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));

		iconFolderDirectory = getPackageDirectory() + "/Icons/";


	}

	@Nullable

	@Override

	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,

			@Nullable Bundle savedInstanceState) {

		View view = inflater.inflate(R.layout.icon_manager_fragment, container, false);

		pDialog = new ProgressDialog(requireContext());

		RecyclerView recyclerView = view.findViewById(R.id.recyclerview);

		if (!new File(getPackageDirectory() + "/Icons/").exists()) {
			showConfirmationDialog(recyclerView, pDialog);
		} else {
                loadIcons(iconFolderDirectory, iconList, recyclerView);
		}

		return view;

	}

	@Override

	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

	}

	
	private void showConfirmationDialog(RecyclerView recyclerView, ProgressDialog progressDialog) {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
		builder.setTitle("Warning!");
		builder.setMessage("Do you want to extract all icons from CodeAssist?");
		builder.setPositiveButton("EXTRACT", (d, w) -> {
		ProgressManager.getInstance().runNonCancelableAsync(() ->startExtractingIcons(progressDialog, recyclerView));
		});
		builder.setNegativeButton("CANCEL", null);
		builder.create().show();
	}

	private void startExtractingIcons(final ProgressDialog progressDialog, RecyclerView recyclerView) {
		Decompress.unzipFromAssets(requireContext(), "Icons.zip", getPackageDirectory());
		ProgressManager.getInstance().runLater(() -> {
			progressDialog.setMessage("Extracting icons");
			progressDialog.setCancelable(false);
			progressDialog.show();
		});
		ProgressManager.getInstance().runLater(() -> {
			if (progressDialog.isShowing()) {
				ProgressManager.getInstance().runNonCancelableAsync(() -> loadIcons(iconFolderDirectory, iconList, recyclerView));
				progressDialog.dismiss();
				if (iconList.size() == 0) {
					Toast.makeText(requireContext(), "Unable to find icons", 3000).show();
				}
			}
		});
	}

	private void loadIcons(String path, ArrayList<String> list, RecyclerView recyclerView) {

		list.clear();

		getFileList(path, list);

		recyclerView.setNestedScrollingEnabled(false);

		recyclerView.setAdapter(new IconAdapter(list, requireContext()));

		recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 4));

	}

	private String getPackageDirectory() {

		return requireContext().getExternalFilesDir(null).getAbsolutePath();

	}

	public static void makeDirs(String path) {

		if (!new File(path).exists()) {

			new File(path).mkdirs();

		}

	}

	public static void getFileList(String source, ArrayList<String> list) {

		File dir = new File(source);

		if (!dir.exists() || dir.isFile())

			return;

		File[] listFiles = dir.listFiles();

		if (listFiles == null || listFiles.length <= 0)

			return;

		if (list == null)

			return;

		list.clear();

		for (File file : listFiles) {

			list.add(file.getAbsolutePath());

		}

	}

}
