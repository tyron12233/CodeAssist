package dev.ide.applog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * A no-op {@link ContentProvider} whose only job is to run {@link #onCreate()} early in the app's startup —
 * before {@code Application.onCreate()} — the same auto-initialization trick androidx-startup and Firebase
 * use. The Android debug build pipeline injects a {@code <provider>} pointing here into the merged manifest
 * (debug builds only), so the system instantiates it at process start and boots {@link IdeLogBridge}, which
 * forwards the app's logs back to the IDE.
 *
 * <p>All the data-access methods are inert; nothing ever queries this provider. It exists purely for its
 * lifecycle callback. It is never present in release builds.
 */
public final class IdeLogBridgeProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        // Never let a bridge failure affect the host app: this is a developer convenience, not app logic.
        try {
            IdeLogBridge.start(getContext());
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
