package com.tyron.code.ui.iconmanager.adapter;

import android.content.Context;

import android.graphics.Bitmap;

import android.graphics.BitmapFactory;

import android.graphics.PorterDuff;

import android.graphics.drawable.Drawable;

import android.graphics.drawable.PictureDrawable;

import android.net.Uri;

import android.os.Bundle;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.ImageView;

import android.widget.LinearLayout;

import android.widget.TextView;

import android.widget.Toast;

import androidx.fragment.app.Fragment;

import androidx.fragment.app.FragmentActivity;

import androidx.fragment.app.FragmentManager;

import com.caverock.androidsvg.SVG;

import com.caverock.androidsvg.SVGParseException;

import com.tyron.code.ui.iconmanager.EditVectorDialogFragment;

import com.tyron.code.ui.iconmanager.IconManagerFragment;

import com.tyron.code.R;

import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayInputStream;

import java.io.File;

import java.io.FileNotFoundException;

import java.io.FileInputStream;

import java.io.InputStream;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;

import java.util.HashMap;

public class IconAdapter extends RecyclerView.Adapter<IconAdapter.ViewHolder> {

	private ArrayList<String> data;	private Context c;

	private LinearLayout base;

	private ImageView icon;

	private TextView name;

	public IconAdapter(ArrayList<String> arr, Context context) {

		data = arr;

		c = context;

	}

	@Override

	public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {

		View v = LayoutInflater.from(c).inflate(R.layout.icon_manager_item, null);

		RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,

				ViewGroup.LayoutParams.WRAP_CONTENT);

		v.setLayoutParams(lp);

		return new ViewHolder(v);

	}

	@Override

	public void onBindViewHolder(ViewHolder holder, final int position) {

		View view = holder.itemView;

		base = view.findViewById(R.id.linear1);

		icon = view.findViewById(R.id.imageview1);

		name = view.findViewById(R.id.textview1);

		if (!data.get(position).isEmpty()) {

			if (new File(data.get(position)).getName().endsWith(".svg")) {

				name.setText(new File(data.get(position)).getName().replace(".svg", ""));

			} else if (new File(data.get(position)).getName().endsWith(".xml")) {

				name.setText(new File(data.get(position)).getName().replace(".xml", ""));

			}

		}

		if (isFile(data.get(position))) {

			if (data.get(position).contains(".svg") || data.get(position).contains(".xml")) {

				try {

					icon.setImageDrawable(loadSvg(data.get(position)));

				} catch (Exception e) {

					Toast.makeText(c, e.toString(), 3000).show();

				}

			}

		}

		icon.setColorFilter(0xFF000000, PorterDuff.Mode.MULTIPLY);

		name.setSelected(true);

		base.setOnClickListener(new View.OnClickListener() {

			@Override

			public void onClick(View view) {

				Bundle bundle = new Bundle();

				bundle.putString("iconPath", data.get(position));

				bundle.putInt("position", position);

				FragmentActivity activity = (FragmentActivity) (c);

				FragmentManager fm = activity.getSupportFragmentManager();

				if (fm.findFragmentByTag(EditVectorDialogFragment.TAG) == null) {

					EditVectorDialogFragment fragment = new EditVectorDialogFragment();

					fragment.setArguments(bundle);

					fragment.show(fm, EditVectorDialogFragment.TAG);

				}

			}

		});

	}

	@Override

	public int getItemCount() {

		return data.size();

	}

	public boolean isFile(String path) {

		if (!new File(path).exists())

			return false;

		return new File(path).isFile();

	}

	public Drawable loadSvg(String path) {

		Drawable drawable = null;

		try {

			FileInputStream fileInputStream = new FileInputStream(new File(path));

			try {

				SVG svg = SVG.getFromInputStream(fileInputStream);

				drawable = new PictureDrawable(svg.renderToPicture());

			} catch (SVGParseException e) {

			}

		} catch (FileNotFoundException e) {

		}

		return drawable;

	}

	public class ViewHolder extends RecyclerView.ViewHolder {

		public ViewHolder(View v) {

			super(v);

		}

	}

}
