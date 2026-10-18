package io.github.elliclee.livingroom;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads the owner's exported history facade; never writes history or runs playback commands. */
final class RecentViewing {
    private static final String AUTHORITY = "com.tcl.cyberui.historyVideofavourites";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY + "/HistoryTableName");
    private static final String[] COLUMNS = {
        "_id", "videoName", "videoImgUrl", "episodeName", "packageName", "srcApp", "receivedtime"
    };
    private static final int MAX_ROWS = 5;
    private static volatile Diagnostic last = new Diagnostic(
        "not_queried", "", 0, 0, 0, null, null);

    static final class Snapshot {
        final String status;
        final String error;
        final int rowsSeen;
        final Item item;
        // Provider package values of the rows read, most recent first; used only to rank apps, never exported.
        final List<String> packages;

        Snapshot(String status, String error, int rowsSeen, Item item, List<String> packages) {
            this.status = status;
            this.error = error;
            this.rowsSeen = rowsSeen;
            this.item = item;
            this.packages = Collections.unmodifiableList(packages);
        }
    }

    static final class Item {
        final String title;
        final String episode;
        final String imageUri;
        // Preserve the provider's package value, including null; do not infer an application.
        final String packageName;
        // A verified PackageManager application label, or empty when it could not be verified.
        final String sourceLabel;

        Item(String title, String episode, String imageUri, String packageName, String sourceLabel) {
            this.title = title;
            this.episode = episode;
            this.imageUri = imageUri;
            this.packageName = packageName;
            this.sourceLabel = sourceLabel;
        }
    }

    /** Call from a worker thread. Artwork reachability is left to the image loader. */
    static Snapshot read(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        long capturedAt = System.currentTimeMillis();
        long started = SystemClock.elapsedRealtime();
        ProviderInfo provider = null;
        Boolean readGranted = null;
        int rowsSeen = 0;
        List<String> watched = new ArrayList<>();
        try {
            PackageManager packages = context.getPackageManager();
            provider = packages.resolveContentProvider(AUTHORITY, 0);
            if (provider == null || !provider.exported || !provider.enabled
                    || (provider.applicationInfo != null && !provider.applicationInfo.enabled)) {
                return finish("unsupported", "", rowsSeen, null,
                    capturedAt, started, provider, readGranted, watched);
            }
            String permission = provider.readPermission;
            if (permission != null && !permission.isEmpty()) {
                readGranted = context.checkSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED;
                if (!readGranted) {
                    return finish("denied", "", rowsSeen, null,
                        capturedAt, started, provider, readGranted, watched);
                }
            }
            try (Cursor cursor = context.getContentResolver().query(
                    URI, COLUMNS, null, null, "receivedtime DESC LIMIT 5")) {
                if (cursor == null) {
                    return finish("query_error", "", rowsSeen, null,
                        capturedAt, started, provider, readGranted, watched);
                }
                int titleColumn = cursor.getColumnIndexOrThrow("videoName");
                int imageColumn = cursor.getColumnIndexOrThrow("videoImgUrl");
                int episodeColumn = cursor.getColumnIndex("episodeName");
                int packageColumn = cursor.getColumnIndex("packageName");
                Item item = null;
                while (rowsSeen < MAX_ROWS && cursor.moveToNext()) {
                    rowsSeen++;
                    String packageName = value(cursor, packageColumn);
                    if (packageName != null && !packageName.isEmpty() && !watched.contains(packageName)) watched.add(packageName);
                    if (item != null) continue;
                    String title = text(value(cursor, titleColumn));
                    String image = allowedArtwork(value(cursor, imageColumn));
                    if (title.isEmpty() || image == null) continue;
                    item = new Item(title, text(value(cursor, episodeColumn)), image,
                        packageName, installedLabel(packages, packageName));
                }
                return finish(item != null ? "available" : rowsSeen == 0 ? "empty" : "invalid_rows", "", rowsSeen, item,
                    capturedAt, started, provider, readGranted, watched);
            }
        } catch (SecurityException failure) {
            return finish("denied", failure.getClass().getName(), rowsSeen, null,
                capturedAt, started, provider, readGranted, watched);
        } catch (RuntimeException failure) {
            return finish("query_error", failure.getClass().getName(), rowsSeen, null,
                capturedAt, started, provider, readGranted, watched);
        }
    }

    /** Returns only the last completed attempt's metadata; does not query or retain an Item. */
    static JSONObject diagnostics(Context context) {
        Diagnostic state = last;
        try {
            JSONObject provider = new JSONObject()
                .put("status", state.providerName == null ? "not_resolved" : "resolved")
                .put("package", nullable(state.providerPackage))
                .put("name", nullable(state.providerName))
                .put("authority", AUTHORITY)
                .put("exported", nullable(state.exported))
                .put("enabled", nullable(state.enabled))
                .put("application_enabled", nullable(state.applicationEnabled))
                .put("read_permission", nullable(state.readPermission))
                .put("read_permission_granted", nullable(state.readGranted));
            return new JSONObject().put("format_version", 1)
                .put("status", state.status).put("error_class", state.error)
                .put("rows_seen", state.rowsSeen).put("captured_at_epoch_ms", state.capturedAt)
                .put("query_elapsed_ms", state.elapsed).put("provider", provider);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Snapshot finish(String status, String error, int rowsSeen, Item item,
            long capturedAt, long started, ProviderInfo provider, Boolean readGranted, List<String> watched) {
        last = new Diagnostic(status, error, rowsSeen, capturedAt,
            SystemClock.elapsedRealtime() - started, provider, readGranted);
        return new Snapshot(status, error, rowsSeen, item, watched);
    }

    private static String value(Cursor cursor, int column) {
        return column < 0 || cursor.isNull(column) ? null : cursor.getString(column);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String allowedArtwork(String raw) {
        String value = text(raw);
        if (value.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (scheme == null || !uri.isHierarchical()) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return uri.getHost() == null || uri.getHost().isEmpty() ? null : value;
            }
            if ("content".equals(scheme)) {
                return uri.getAuthority() == null || uri.getAuthority().isEmpty() ? null : value;
            }
        } catch (IllegalArgumentException invalidUri) {
            return null;
        }
        return null;
    }

    private static String installedLabel(PackageManager packages, String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        try {
            ApplicationInfo application = packages.getApplicationInfo(packageName, 0);
            CharSequence label = packages.getApplicationLabel(application);
            return label == null ? "" : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // Package visibility or a package-manager failure leaves the source unverified.
            return "";
        }
    }

    private static Object nullable(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    /** Contains no row values, Item, title, artwork URI, or playback command. */
    private static final class Diagnostic {
        final String status, error, providerPackage, providerName, readPermission;
        final int rowsSeen;
        final long capturedAt, elapsed;
        final Boolean exported, enabled, applicationEnabled, readGranted;

        Diagnostic(String status, String error, int rowsSeen, long capturedAt, long elapsed,
                ProviderInfo provider, Boolean readGranted) {
            this.status = status;
            this.error = error;
            this.rowsSeen = rowsSeen;
            this.capturedAt = capturedAt;
            this.elapsed = elapsed;
            this.providerPackage = provider == null ? null : provider.packageName;
            this.providerName = provider == null ? null : provider.name;
            this.readPermission = provider == null ? null : provider.readPermission;
            this.exported = provider == null ? null : provider.exported;
            this.enabled = provider == null ? null : provider.enabled;
            this.applicationEnabled = provider == null || provider.applicationInfo == null
                ? null : provider.applicationInfo.enabled;
            this.readGranted = readGranted;
        }
    }
}
