package com.tyron.code.ui.iconmanager.loader;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import com.tyron.code.R;
import com.tyron.code.ui.iconmanager.loader.RadialProgressView;

public class LoaderDialog {
	AlertDialog dialog;

	public LoaderDialog(Context c) {

		dialog = new AlertDialog.Builder(c).create();
		View inflate = LayoutInflater.from(c).inflate(R.layout.loader, null);
		dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
		dialog.setView(inflate);
		LinearLayout l1 = (LinearLayout) inflate.findViewById(R.id.l1);
		LinearLayout l2 = (LinearLayout) inflate.findViewById(R.id.l2);
		LinearLayout container = (LinearLayout) inflate.findViewById(R.id.l3);

		GradientDrawable gd = new GradientDrawable();
		gd.setColor(Color.parseColor("#E0E0E0")); /* color */
		gd.setCornerRadius(40); /* radius */
		gd.setStroke(0, Color.WHITE); /* stroke heigth and color */
		l2.setBackground(gd);

		RadialProgressView rp = new RadialProgressView(c);
		container.addView(rp);

	}

	public LoaderDialog show() {
		dialog.show();
		return this;
	}

	public LoaderDialog dismiss() {
		if (dialog.isShowing()) {
			dialog.dismiss();
		}
		return this;
	}

}
