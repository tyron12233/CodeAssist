package com.tyron.code.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import com.tyron.code.ApplicationLoader;

@SuppressWarnings("unused")
public class AndroidUtilities {
    
    public static int dp(float px) {
        return Math.round(ApplicationLoader.applicationContext
				.getResources().getDisplayMetrics().density * px);
    }

	/**
	 * Converts a dp value into px that can be applied on margins, paddings etc
	 * @param dp The dp value that will be converted into px
	 * @return The converted px value from the dp argument given
	 */
	public static int dpToPx(float dp) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
				dp, ApplicationLoader.applicationContext.getResources().getDisplayMetrics()));
	}

	public static void hideKeyboard(View view) {
		if (view == null) {
			return;
		}
		try {
			InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Activity.INPUT_METHOD_SERVICE);
			if (!imm.isActive()) {
				return;
			}
			imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
		} catch (Exception e) {
			Log.d("AndroidUtilities", "Failed to close keyboard " + e.getMessage());
		}
	}

	public static void copyToClipboard(String text) {
		ClipboardManager clipboard = (ClipboardManager) ApplicationLoader.applicationContext
				.getSystemService(Context.CLIPBOARD_SERVICE);

		ClipData clip = ClipData.newPlainText("", text); // is label important?
		clipboard.setPrimaryClip(clip);
	}

	public static void copyToClipboard(String text, boolean showToast) {
		copyToClipboard(text);

		if (showToast) ApplicationLoader.showToast("Copied \"" + text + "\" to clipboard");
	}
	
	public static int getHeight(ViewGroup viewGroup) {

		int height = 0;
		for (int i = 0; i < viewGroup.getChildCount(); i++) {
			View view = viewGroup.getChildAt(i);
			
			height += view.getMeasuredHeight();
		}
		
		return height;
	}

	public static int getRowCount(int itemWidth) {
		DisplayMetrics displayMetrics = ApplicationLoader.applicationContext
				.getResources().getDisplayMetrics();

		return (displayMetrics.widthPixels / itemWidth);
	}
}
