package com.tyron.common.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.common.ApplicationProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@SuppressWarnings("unused")
public class AndroidUtilities {

	public static void showToast(String message) {
		Toast.makeText(ApplicationProvider.getApplicationContext(), message, Toast.LENGTH_LONG).show();
	}

	public static void showToast(@StringRes int id) {
		Toast.makeText(ApplicationProvider.getApplicationContext(), id, Toast.LENGTH_SHORT).show();
	}
    
    public static int dp(float px) {
        return Math.round(ApplicationProvider.getApplicationContext()
				.getResources().getDisplayMetrics().density * px);
    }

    public static void setMargins(View view, int startMargin, int topMargin, int endMargin, int bottomMargin) {
		ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
		layoutParams.setMargins(dp(startMargin), dp(topMargin), dp(endMargin), dp(bottomMargin));
	}

	/**
	 * Converts a dp value into px that can be applied on margins, paddings etc
	 * @param dp The dp value that will be converted into px
	 * @return The converted px value from the dp argument given
	 */
	public static int dpToPx(float dp) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
				dp, ApplicationProvider.getApplicationContext().getResources().getDisplayMetrics()));
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

	public static String calculateMD5(File updateFile) {
		InputStream is;
		try {
			is = new FileInputStream(updateFile);
		} catch (FileNotFoundException e) {
			Log.e("calculateMD5", "Exception while getting FileInputStream", e);
			return null;
		}

		return calculateMD5(is);
	}

	public static String calculateMD5(InputStream is) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			Log.e("calculateMD5", "Exception while getting Digest", e);
			return null;
		}

		byte[] buffer = new byte[8192];
		int read;
		try {
			while ((read = is.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
			byte[] md5sum = digest.digest();
			BigInteger bigInt = new BigInteger(1, md5sum);
			String output = bigInt.toString(16);
			// Fill to 32 chars
			output = String.format("%32s", output).replace(' ', '0');
			return output;
		} catch (IOException e) {
			throw new RuntimeException("Unable to process file for MD5", e);
		} finally {
			try {
				is.close();
			} catch (IOException e) {
				Log.e("calculateMD5", "Exception on closing MD5 input stream", e);
			}
		}
	}

	public static void showSimpleAlert(Context context, @StringRes int title, @StringRes int message) {
		showSimpleAlert(context, context.getString(title), context.getString(message));
	}

	public static void showSimpleAlert(@NonNull Context context, @StringRes int title, String message) {
		showSimpleAlert(context, context.getString(title), message);
	}

	public static void showSimpleAlert(Context context, String title, String message) {
		new MaterialAlertDialogBuilder(context)
				.setTitle(title)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	public static void copyToClipboard(String text) {
		ClipboardManager clipboard = (ClipboardManager) ApplicationProvider.getApplicationContext()
				.getSystemService(Context.CLIPBOARD_SERVICE);

		ClipData clip = ClipData.newPlainText("", text); // is label important?
		clipboard.setPrimaryClip(clip);
	}

	@Nullable
	public static CharSequence getPrimaryClip() {
		ClipboardManager clipboard = (ClipboardManager) ApplicationProvider.getApplicationContext()
				.getSystemService(Context.CLIPBOARD_SERVICE);
		ClipData primaryClip = clipboard.getPrimaryClip();
		if (primaryClip != null) {
			if (primaryClip.getItemCount() >= 1) {
				return primaryClip.getItemAt(0).getText();
			}
		}
		return null;
	}

	public static void copyToClipboard(String text, boolean showToast) {
		copyToClipboard(text);

		if (showToast) showToast("Copied \"" + text + "\" to clipboard");
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
		DisplayMetrics displayMetrics = ApplicationProvider.getApplicationContext()
				.getResources().getDisplayMetrics();

		return (displayMetrics.widthPixels / itemWidth);
	}
}
