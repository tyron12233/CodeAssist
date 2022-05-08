package com.tyron.code.ui.iconmanager;

import android.graphics.PorterDuff;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PictureDrawable;

import android.text.Editable;
import android.view.View;
import android.os.Bundle;
import android.app.Dialog;

import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.caverock.androidsvg.SVG;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.tyron.code.R;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.util.SingleTextWatcher;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class EditVectorDialogFragment extends DialogFragment {

	public static final String TAG = EditVectorDialogFragment.class.getSimpleName();	
	public static final String ADD_KEY = "addVector";
	private String iconPath, projectResourceDirectory;

	@SuppressWarnings("ConstantConditions")
	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

		Bundle bundle = this.getArguments();
		if (bundle != null) {
			iconPath = bundle.getString("iconPath");
			projectResourceDirectory = ProjectManager.getInstance().getCurrentProject().getRootFile().getAbsolutePath() + "/app/src/main/res/drawable/";
		}

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
		View inflate = getLayoutInflater().inflate(R.layout.create_vector_dialog, null);
		TextInputLayout textinput1 = (TextInputLayout) inflate.findViewById(R.id.textinputlayout1);
		TextInputLayout textinput2 = (TextInputLayout) inflate.findViewById(R.id.textinputlayout2);
		TextInputLayout textinput3 = (TextInputLayout) inflate.findViewById(R.id.textinputlayout3);
		TextInputLayout textinput4 = (TextInputLayout) inflate.findViewById(R.id.textinputlayout4);
		TextInputLayout textinput5 = (TextInputLayout) inflate.findViewById(R.id.textinputlayout5);
		TextInputEditText name = (TextInputEditText) inflate.findViewById(R.id.name);
		TextInputEditText height = (TextInputEditText) inflate.findViewById(R.id.height);
		TextInputEditText width = (TextInputEditText) inflate.findViewById(R.id.width);
		TextInputEditText color = (TextInputEditText) inflate.findViewById(R.id.color);
		TextInputEditText path = (TextInputEditText) inflate.findViewById(R.id.path);
		ImageView icon = (ImageView) inflate.findViewById(R.id.icon);
		LinearLayout container = (LinearLayout) inflate.findViewById(R.id.container);
		LinearLayout round = (LinearLayout) inflate.findViewById(R.id.round);

		round.setBackgroundColor(0XFF000000);
		path.setEnabled(false);
		path.setText(projectResourceDirectory);

		textinput4.setEndIconOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				if (!color.getText().toString().trim().startsWith("#")) {
					Toast.makeText(requireContext(), "Invalid color code", 3000).show();
				} else {
					try {
						icon.setColorFilter(Color.parseColor(color.getText().toString().trim()), PorterDuff.Mode.MULTIPLY);
						round.setBackgroundColor(Color.parseColor(color.getText().toString().trim()));
					} catch (Exception e) {
						Toast.makeText(requireContext(), e.toString(), 3000).show();
					}
				}
			}
		});

		builder.setView(inflate);

		if (iconPath.contains(".svg")) {
			name.setText(new File(iconPath).getName().replace(".svg", ""));
		} else if (iconPath.contains(".xml")) {
			name.setText(new File(iconPath).getName().replace(".xml", ""));
		}

		icon.setImageDrawable(loadSvg(iconPath));
		
		builder.setPositiveButton("Create", (d, w) -> {
			generateSvg2Vector(name.getText().toString().trim(), width.getText().toString().trim(), height.getText().toString().trim(), color.getText().toString().trim(), iconPath, projectResourceDirectory);
		});

		builder.setNegativeButton("Cancel", null);
		
		AlertDialog dialog = builder.create();
		dialog.setOnShowListener(d -> {
			final Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
			SingleTextWatcher textWatcher = new SingleTextWatcher() {
					@Override
					public void afterTextChanged(Editable editable) {
						boolean valid = validate(name, height, width, color, path);
						positiveButton.setEnabled(valid);
					}
				};

				name.addTextChangedListener(textWatcher);
				height.addTextChangedListener(textWatcher);
				width.addTextChangedListener(textWatcher);
				color.addTextChangedListener(textWatcher);
				path.addTextChangedListener(textWatcher);
		});

		return dialog;
	}

	private void generateSvg2Vector(String name, String width, String height, String color, String source, String destination) {

		File svgPath = new File(source);
		DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();

		try {
			DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
			Document document = documentBuilder.parse(svgPath);
			NodeList nodeList = document.getElementsByTagName("path");
			if (nodeList.getLength() > 0) {
				Element element = (Element) nodeList.item(0);
				String a = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:width=\""

						+ width + "dp" + "\"\n    android:height=\"" + height + "dp"

						+ "\"\n    android:viewportWidth=\"" + width + "\"\n    android:viewportHeight=\"" + height

						+ "\"\n    android:tint=\"" + color

						+ "\">\n  <path\n      android:fillColor=\"@android:color/white\"\n      android:pathData=\""

						+ element.getAttribute("d") + "\"/>\n</vector>\n";

				byte[] vectorText = a.getBytes(StandardCharsets.UTF_8);

				Files.write(Paths.get(new File(projectResourceDirectory + name + ".xml").toURI()), vectorText, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

			}
		} catch (Exception e) {
			Toast.makeText(requireContext(), e.toString(), 3000).show();
		}
	}

	private boolean validate(EditText name, EditText height, EditText width, EditText color, EditText path) {
		if (name.getText().toString().trim().isEmpty() && name.getText().toString().trim().endsWith(".xml") && name.getText().toString().endsWith(".svg")) {
			return false;
		} else if (height.getText().toString().trim().isEmpty()) {
			return false;
		} else if (width.getText().toString().trim().isEmpty()) {
			return false;
		} else if (color.getText().toString().trim().isEmpty()) {
			return false;
		} else if (path.getText().toString().trim().isEmpty()) {
			return false;
		}
		return !name.getText().toString().contains(".xml") && !name.getText().toString().contains(".svg");
	}

	private Drawable loadSvg(String path) {
		Drawable drawable = null;
		try {
			FileInputStream fileInputStream = new FileInputStream(new File(path));
			SVG svg = SVG.getFromInputStream(fileInputStream);
			drawable = new PictureDrawable(svg.renderToPicture());
		} catch (Exception e) {
			Toast.makeText(requireContext(), e.toString(), 3000).show();
		}
		return drawable;
	}
}
