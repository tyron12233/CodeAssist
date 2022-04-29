package com.tyron.code.ui.iconmanager.util;

import android.content.Context;
import android.widget.Toast;

public class Utils {

	public static void toast(Context c, String content) {
		Toast.makeText(c, content, 3000).show();
	}

}