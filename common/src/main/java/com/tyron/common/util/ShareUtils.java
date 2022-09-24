package com.tyron.common.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.jetbrains.annotations.Nullable;

public class ShareUtils {

    private static final String LOG_TAG = "ShareUtils";

    /**
     * Open the system app chooser that allows the user to select which app to send the intent.
     *
     * @param context The context for operations.
     * @param intent The intent that describes the choices that should be shown.
     * @param title The title for choose menu.
     */
    public static void openSystemAppChooser(final Context context, final Intent intent, final String title) {
        if (context == null) return;

        final Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, intent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, title);
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chooserIntent);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to open system chooser for:\n" + IntentUtils.getIntentString(chooserIntent), e);
        }
    }

    /**
     * Share text.
     *
     * @param context The context for operations.
     * @param subject The subject for sharing.
     * @param text The text to share.
     */
    public static void shareText(final Context context, final String subject, final String text) {
        shareText(context, subject, text, null);
    }

    /**
     * Share text.
     *
     * @param context The context for operations.
     * @param subject The subject for sharing.
     * @param text The text to share.
     * @param title The title for share menu.
     */
    public static void shareText(final Context context, final String subject, final String text, @Nullable final String title) {
        if (context == null || text == null) return;

        final Intent shareTextIntent = new Intent(Intent.ACTION_SEND);
        shareTextIntent.setType("text/plain");
        shareTextIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        shareTextIntent.putExtra(Intent.EXTRA_TEXT, DataUtils.getTruncatedCommandOutput(text, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES, true, false, false));

        openSystemAppChooser(context, shareTextIntent, DataUtils.isNullOrEmpty(title) ? "Share with" : title);
    }

    /**
     * Open a url.
     *
     * @param context The context for operations.
     * @param url The url to open.
     */
    public static void openUrl(final Context context, final String url) {
        if (context == null || url == null || url.isEmpty()) return;
        Uri uri = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // If no activity found to handle intent, show system chooser
            openSystemAppChooser(context, intent, "Open url with");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to open url \"" + url + "\"", e);
        }
    }
}
