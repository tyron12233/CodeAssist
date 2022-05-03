package com.tyron.code.ui.iconmanager.util;

import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.os.Environment;
import android.text.TextUtils;
import android.content.ContentUris;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import android.widget.Toast;
import android.provider.DocumentsContract;
import android.content.ContentResolver;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.File;
import java.net.URLDecoder;
import android.net.Uri;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;
import java.util.ArrayList;

public class FileUtil {

	public static String getPackageDir(Context c) {
		return c.getExternalFilesDir(null).getAbsolutePath();
	}

	public static boolean isExternalStorageDocument(Uri uri) {
		return "com.android.externalstorage.documents".equals(uri.getAuthority());
	}

	public static boolean isDownloadsDocument(Uri uri) {
		return "com.android.providers.downloads.documents".equals(uri.getAuthority());
	}

	public static boolean isMediaDocument(Uri uri) {
		return "com.android.providers.media.documents".equals(uri.getAuthority());
	}

	public static boolean exists(String path) {
		return new File(path).exists();
	}

	public static void createNewFile(String path) {
		int lastSep = path.lastIndexOf(File.separator);
		if (lastSep > 0) {
			String dirPath = path.substring(0, lastSep);
			makeDir(dirPath);
		}

		File file = new File(path);

		try {
			if (!file.exists())
				file.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static String readFile(String path) {
		createNewFile(path);

		StringBuilder sb = new StringBuilder();
		FileReader fr = null;
		try {
			fr = new FileReader(new File(path));

			char[] buff = new char[1024];
			int length = 0;

			while ((length = fr.read(buff)) > 0) {
				sb.append(new String(buff, 0, length));
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (fr != null) {
				try {
					fr.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		return sb.toString();
	}

	public static void makeDir(String path) {
		if (!exists(path)) {
			File file = new File(path);
			file.mkdirs();
		}
	}

	public static void deleteFile(String path) {

		if (exists(path)) {
			File file = new File(path);
			file.delete();
		}

	}

	public static void copyFile(String sourcePath, String destPath) {
		if (!exists(sourcePath))
			return;
		createNewFile(destPath);

		FileInputStream fis = null;
		FileOutputStream fos = null;

		try {
			fis = new FileInputStream(sourcePath);
			fos = new FileOutputStream(destPath, false);

			byte[] buff = new byte[1024];
			int length = 0;

			while ((length = fis.read(buff)) > 0) {
				fos.write(buff, 0, length);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public static String convertUriToFilePath(final Context context, final Uri uri) {
		String path = null;
		if (DocumentsContract.isDocumentUri(context, uri)) {
			if (isExternalStorageDocument(uri)) {
				final String docId = DocumentsContract.getDocumentId(uri);
				final String[] split = docId.split(":");
				final String type = split[0];

				if ("primary".equalsIgnoreCase(type)) {
					path = Environment.getExternalStorageDirectory() + "/" + split[1];
				}
			} else if (isDownloadsDocument(uri)) {
				final String id = DocumentsContract.getDocumentId(uri);

				if (!TextUtils.isEmpty(id)) {
					if (id.startsWith("raw:")) {
						return id.replaceFirst("raw:", "");
					}
				}

				final Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"),
						Long.valueOf(id));

				path = getDataColumn(context, contentUri, null, null);
			} else if (isMediaDocument(uri)) {
				final String docId = DocumentsContract.getDocumentId(uri);
				final String[] split = docId.split(":");
				final String type = split[0];

				Uri contentUri = null;
				if ("image".equals(type)) {
					contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
				} else if ("video".equals(type)) {
					contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
				} else if ("audio".equals(type)) {
					contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
				}

				final String selection = "_id=?";
				final String[] selectionArgs = new String[] { split[1] };

				path = getDataColumn(context, contentUri, selection, selectionArgs);
			}
		} else if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
			path = getDataColumn(context, uri, null, null);
		} else if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
			path = uri.getPath();
		}

		if (path != null) {
			try {
				return URLDecoder.decode(path, "UTF-8");
			} catch (Exception e) {
				return null;
			}
		}
		return null;
	}

	private static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
		final String column = MediaStore.Images.Media.DATA;
		final String[] projection = { column };

		try (Cursor cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				final int column_index = cursor.getColumnIndexOrThrow(column);
				return cursor.getString(column_index);
			}
		} catch (Exception e) {

		}
		return null;
	}

	public static Bitmap decodeSampleBitmapFromPath(String path, int reqWidth, int reqHeight) {
		final BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(path, options);

		options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

		options.inJustDecodeBounds = false;
		return BitmapFactory.decodeFile(path, options);
	}

	public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
		final int width = options.outWidth;
		final int height = options.outHeight;
		int inSampleSize = 1;

		if (height > reqHeight || width > reqWidth) {
			final int halfHeight = height / 2;
			final int halfWidth = width / 2;

			while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
				inSampleSize *= 2;
			}
		}

		return inSampleSize;
	}


	public static File bitmapToFile(Context c, Bitmap bmp, String name) {

		File file = null;

		try {

			file = new File(getPackageDir(c).concat("/material-icons-pack/materialiconsoutlined/preview-packs/".concat(name)));
			file.createNewFile();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			bmp.compress(Bitmap.CompressFormat.PNG, 0, bos);
			byte[] bitmapdata = bos.toByteArray();
			FileOutputStream fos = new FileOutputStream(file);
			fos.write(bitmapdata);
			fos.flush();
			fos.close();
			File path = new File(getPackageDir(c).concat("/material-icons-pack/res"));
			path.delete();
			return file;

		} catch (Exception e) {

			e.printStackTrace();

			return file;
		}
	}

	public static void writeFile(String path, String str) {
		createNewFile(path);
		FileWriter fileWriter = null;

		try {
			fileWriter = new FileWriter(new File(path), false);
			fileWriter.write(str);
			fileWriter.flush();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if (fileWriter != null)
					fileWriter.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public static void listDir(String path, ArrayList<String> list) {
		File dir = new File(path);
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
