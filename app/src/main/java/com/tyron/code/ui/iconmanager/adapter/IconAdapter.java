package com.tyron.code.ui.iconmanager.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.tyron.code.ui.iconmanager.IconManagerActivity;
import com.tyron.code.R;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

public class IconAdapter extends RecyclerView.Adapter<IconAdapter.ViewHolder> {

    ArrayList<String> data;
    Context c;
    LinearLayout base;
    ImageView icon;
    TextView name;

    public IconAdapter(ArrayList<String> arr, Context context) {
        data = arr;
        c = context;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(c).inflate(R.layout.item, null);
        RecyclerView.LayoutParams lp =
                new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
            if (Uri.parse(Uri.parse(data.get(position)).getLastPathSegment())
                    .getLastPathSegment()
                    .startsWith("outline_")) {
                name.setText(
                        Uri.parse(
                                        Uri.parse(data.get(position))
                                                .getLastPathSegment())
                                .getLastPathSegment()
                                .replace(".png", ""));
            }
        }
        if (isFile(data.get(position))) {
            if (data.get(position).contains(".png")) {
                icon.setImageBitmap(
                        decodeSampleBitmapFromPath(
                                data.get(position), 1024, 1024));
            }
        }
        icon.setColorFilter(0xFF000000, PorterDuff.Mode.MULTIPLY);
        name.setSelected(true);
	    base.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
		    IconManagerActivity.editVectorDialog(c, position);
		   }
                });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public boolean isExistFile(String path) {
        File file = new File(path);
        return file.exists();
    }

    public boolean isFile(String path) {
        if (!isExistFile(path)) return false;
        return new File(path).isFile();
    }

    public Bitmap decodeSampleBitmapFromPath(String path, int reqWidth, int reqHeight) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, options);
    }

    public int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int width = options.outWidth;
        final int height = options.outHeight;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        public ViewHolder(View v) {
            super(v);
        }
    }
}
