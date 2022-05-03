package com.tyron.code.ui.iconmanager;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.text.TextUtils;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.tyron.code.ui.iconmanager.loader.LoaderDialog;
import com.tyron.code.ui.iconmanager.util.FileUtil;
import android.widget.Button;
import androidx.recyclerview.widget.RecyclerView;
import com.tyron.code.ui.iconmanager.adapter.IconAdapter;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileOutputStream;
import java.net.URLDecoder;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.code.ui.project.ProjectManager;
import org.apache.commons.io.FileUtils;
import com.tyron.common.util.AndroidUtilities;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class IconManagerActivity extends AppCompatActivity {
	
	
	//mainTask: codeCleanUp :)

	FloatingActionButton fab;
	Toolbar toolbar;
	RecyclerView recyclerview1;
	public static String desPath, resPath, name, icon, vector, unziped_vector, f, project_path, from, from2, samplePath;
	int n = 0;
        //ArrayList
	ArrayList<String> list = new ArrayList<>();
	ArrayList<String> scanner1 = new ArrayList<>();
	ArrayList<String> scanner2 = new ArrayList<>();
        public static ArrayList<String> scanner3 = new ArrayList<>();
        public static ArrayList<String> scanner4 = new ArrayList<>();
        //
	SwipeRefreshLayout swipeRefreshLayout;
	Project mProject;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		fab = findViewById(R.id.fab);
		toolbar = findViewById(R.id.toolbar);
		recyclerview1 = findViewById(R.id.recyclerview1);
		swipeRefreshLayout = findViewById(R.id.s1);

		//Strings
		desPath = FileUtil.getPackageDir(this) + "/material-icons-pack/materialiconsoutlined/preview-packs/";
		String mPath = FileUtil.getPackageDir(this) + "/material-icons-pack/";
		resPath = mPath + "res";
                String v1 = ProjectManager.getInstance().getCurrentProject().getRootFile().getAbsolutePath();
                String v2 = "/app/src/main/res/drawable/";
		project_path = v1 + v2;
                samplePath = mPath + "sample";
                //     

		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(false);
		getSupportActionBar().setHomeButtonEnabled(false);
		getSupportActionBar().setTitle("Icon Manager");
		fab.setImageResource(R.drawable.outline_add_24);
		getIconList(desPath, recyclerview1);
		makeSomeCheckup();
                makeSomeFileCheckUp();

		fab.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                intent.addCategory(Intent.CATEGORY_OPENABLE);
                                intent.setType("application/zip");
				startActivityForResult(intent, 2000);

			}
		});

		swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
			@Override
			public void onRefresh() {
				getIconList(desPath, recyclerview1);
				swipeRefreshLayout.setRefreshing(false);
			}
		});

	}
        //makes some important checkups
	private void makeSomeCheckup() {
		String checkupFather = FileUtil.getPackageDir(this) + "/material-icons-pack/materialiconsoutlined/";
		FileUtil.makeDir(checkupFather);
                FileUtil.makeDir(samplePath + "/");
		FileUtil.makeDir(checkupFather + "preview-packs/");
		FileUtil.makeDir(checkupFather + "vector-packs/");
	}
        //checks if res folder and sample folder
	private void makeSomeFileCheckUp() {
		
        if(FileUtil.exists(resPath)) {
        try {
	FileUtils.forceDelete(new File(resPath));       
	} catch (Exception e) {
	AndroidUtilities.showToast(e.toString());
	}
        }
	}

	private void getIconList(String path, RecyclerView recyclerView) {
		makeSomeCheckup();
		list.clear();
		FileUtil.listDir(path, list);
		recyclerView.setAdapter(new IconAdapter(list, this));
		recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
		recyclerView.setNestedScrollingEnabled(false);

	}

	private void performUnzipTask(String from, String to) {

		try {
			File outdir = new File(to);
			ZipInputStream zin = new ZipInputStream(new FileInputStream(from));
			ZipEntry entry;
			String name, dir;
			while ((entry = zin.getNextEntry()) != null) {
				name = entry.getName();
				if (entry.isDirectory()) {
					mkdirs(outdir, name);
					continue;
				}

				dir = dirpart(name);
				if (dir != null)
					mkdirs(outdir, dir);

				extractFile(zin, outdir, name);
			}
			zin.close();
		} catch (java.io.IOException e) {
			e.printStackTrace();
		}
	}

	private static void extractFile(ZipInputStream in, File outdir, String name) throws IOException {
		byte[] buffer = new byte[4096];
		BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(new File(outdir, name)));
		int count = -1;
		while ((count = in.read(buffer)) != -1)
			out.write(buffer, 0, count);
		out.close();
	}

	private static void mkdirs(File outdir, String path) {
		File d = new File(outdir, path);
		if (!d.exists())
			d.mkdirs();
	}

	private static String dirpart(String name) {
		int s = name.lastIndexOf(File.separatorChar);
		return s == -1 ? null : name.substring(0, s);

	}

	private void startFullProcess(String from, String to) {

		performUnzipTask(from, to);
		FileUtil.listDir(resPath + "/drawable/", scanner1);

		for (int repeat = 0; repeat < scanner1.size(); repeat++) {
			if (scanner1.get(repeat).contains("_24")) {
				name = scanner1.get(repeat);
			}		
		}
                
                FileUtil.listDir(resPath + "/drawable-xxhdpi/", scanner2);

			for (int repeat = 0; repeat < scanner2.size(); repeat++) {
				if (scanner2.get(repeat).contains("_white_48")) {
					icon = scanner2.get(repeat);
				}
			}

		if (FileUtil.exists(name) || FileUtil.exists(icon)) {

			unziped_vector = FileUtil.readFile(name);
			unziped_vector = unziped_vector.replace("android:height=\"24dp\"", "android:height=\"$height_dp\"");
			unziped_vector = unziped_vector.replace("android:width=\"24dp\"", "android:width=\"$width_dp\"");
			unziped_vector = unziped_vector.replace("android:viewportHeight=\"24\"",
					"android:viewportHeight=\"$height_\"");
			unziped_vector = unziped_vector.replace("android:viewportWidth=\"24\"",
					"android:viewportWidth=\"$width_\"");
			unziped_vector = unziped_vector.replace("android:tint=\"?attr/colorControlNormal\"",
					"android:tint=\"$tint\"");
			FileUtil.writeFile(to + "materialiconsoutlined/vector-packs/" + Uri.parse(name).getLastPathSegment().replace("_24", ""), unziped_vector);

			Bitmap bm = BitmapFactory.decodeFile(new File(icon).getAbsolutePath());
			FileUtil.bitmapToFile(IconManagerActivity.this, bm, Uri.parse(icon).getLastPathSegment().replace("_white_48", ""));

		}

		AndroidUtilities.showToast("Extracted successfully");
		try {
			FileUtils.forceDelete(new File(resPath));
		} catch (Exception e) {
			AndroidUtilities.showToast(e.toString());
		}

	}

	public static void editVectorFileProcess(Context c, String name, String height, String width, String color, int position,
			String to) {

                scanner3.clear();
                String ab = FileUtil.getPackageDir(c) + "/material-icons-pack/materialiconsoutlined/vector-packs/";
                FileUtil.listDir(ab, scanner3);
                from = scanner3.get(position);
		vector = FileUtil.readFile(from);
		vector = vector.replace("$height_", height.trim());
		vector = vector.replace("$width_", width.trim());
		vector = vector.replace("$tint", color.trim());
		if (name.trim().equals("")) {
			FileUtil.writeFile(to.concat(Uri.parse(from).getLastPathSegment()), vector);

		} else {
			FileUtil.writeFile(to.concat(name.trim().concat(".xml")), vector);

		}                

	}

	public static void editVectorDialog(final Context c, int position) {
                scanner3.clear();
                String d = FileUtil.getPackageDir(c) + "/material-icons-pack/materialiconsoutlined/preview-packs/";
                FileUtil.listDir(d,scanner3);
                from2 = scanner3.get(position);
		final AlertDialog dialog1 = new MaterialAlertDialogBuilder(c).create();
		View inflate = LayoutInflater.from(c).inflate(R.layout.custom_asset_dialog, null);
		dialog1.setView(inflate);
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

		Button b1 = (Button) inflate.findViewById(R.id.b1);

		LinearLayout container = (LinearLayout) inflate.findViewById(R.id.container);
		textinput1.setHint("Name");
		textinput2.setHint("Height");
		textinput3.setHint("Width");
		textinput4.setHint("Color");
		textinput5.setHint("Path");
		path.setEnabled(false);
		icon.setColorFilter(0xFF000000, PorterDuff.Mode.MULTIPLY);
		final String iconName = Uri.parse(from2).getLastPathSegment();
		final String finalIconName = iconName.replace(".png", "");
		name.setText(finalIconName);
		path.setText(project_path);
                color.setText("#000000");
                textinput4.setEndIconOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				if (!color.getText().toString().startsWith("#")) {
					AndroidUtilities.showToast("Invalid color code");
				} else {
					try {
						icon.setColorFilter(Color.parseColor(color.getText().toString()), PorterDuff.Mode.MULTIPLY);
					} catch (Exception e) {
						AndroidUtilities.showToast("Invalid color code");
					}
				}
			}
		});               
		
		icon.setImageBitmap(FileUtil.decodeSampleBitmapFromPath(from2, 1024, 1024));
		b1.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				if (name.getText().toString().trim().equals("")) {

				} else {
					if (height.getText().toString().trim().equals("")) {

					} else {
						if (width.getText().toString().trim().equals("")) {

						} else {
							if (color.getText().toString().trim().equals("")) {

							} else {
								editVectorFileProcess(c, name.getText().toString().trim(),
										height.getText().toString().trim(), width.getText().toString().trim(),
										color.getText().toString().trim(), position, path.getText().toString());
                                                                AndroidUtilities.showToast("Icon has been added to your project");
								dialog1.dismiss();
							}
						}
					}
				}
			}
		});
		dialog1.show();
	}


	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {
		case 2000:
			if (resultCode == Activity.RESULT_OK) {
				if (data == null) {

				} else {

					Uri uri = data.getData();
					if (uri != null) {
                                                BufferedReader reader = null; 
                                                InputStream in = null;
                                                OutputStream out = null;
                                                String path = FileUtil.convertUriToFilePath(this,uri);
						f = Uri.parse(path).getLastPathSegment();
                                                try {
	                                          try {
		
		                                   in = getContentResolver().openInputStream(uri);
		                                   out = new FileOutputStream(new File(samplePath+"/"+f));
		                                   byte[] buffer = new byte[1024];
		                                   int len;
		                                   while ((len = in.read(buffer)) != -1) {
			                           out.write(buffer, 0, len);
		                                   }
	                                           } finally {
		                                   if (in != null) {
			                           in.close();
		                                   }
		                                   if (out != null){
			                           out.close();
		                                   }
	                                           }
                                                   }catch(Exception e) {
	                                           AndroidUtilities.showToast(e.toString());
                                                }                                                


						final LoaderDialog loaderDialog = new LoaderDialog(this).show();
                                                String processPath = samplePath + "/" +f;
                                                if(FileUtil.exists(processPath)){                                           
						startFullProcess(processPath, FileUtil.getPackageDir(IconManagerActivity.this) + "/material-icons-pack/");
                                                }
						TimerTask task;
						Timer timer = new Timer();
						task = new TimerTask() {
							@Override
							public void run() {
								runOnUiThread(new Runnable() {
									@Override
									public void run() {
										loaderDialog.dismiss();
										getIconList(desPath, recyclerview1);
                                                                                try {
                                                                                FileUtils.forceDelete(new File(samplePath));
                                                                                } catch(Exception e) {
                                                                                AndroidUtilities.showToast(e.toString());
                                                                                }
									}
								});
							}
						};
						timer.schedule(task, 3000);

					}
				}
			}

			break;

		}

	}

}
