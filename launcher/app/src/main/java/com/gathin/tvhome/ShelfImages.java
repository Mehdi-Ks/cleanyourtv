package com.gathin.tvhome;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures for the top shelf cards. The only network use in the launcher: it fetches the poster or thumbnail URL an
 * app published for its own Watch Next / channel program, nothing else, and only when that card is shown. Pictures
 * are scaled down to card size and kept in a small memory cache; nothing is written to disk.
 */
final class ShelfImages {

    private static final String TAG = "TvHome.ShelfImages";
    private static final int MAX_BYTES = 4 * 1024 * 1024;   // bigger downloads are not a card picture
    private static final int TIMEOUT_MS = 6000;

    private final ContentResolver resolver;
    private final int targetWidth;
    private static final long MAX_PIXELS = 1_000_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    ShelfImages(ContentResolver resolver, int targetWidthPx) {
        this.resolver = resolver;
        this.targetWidth = targetWidthPx;
    }

    /** Shows uri in view, now from the cache or later from a background load. The view's tag guards reuse. */
    void load(String uri, ImageView view) {
        view.setTag(uri);
        view.setImageBitmap(null);
        if (uri == null || pool.isShutdown()) return;
        Bitmap hit = cache.get(uri);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap bmp = fetch(uri);
            if (bmp == null) return;
            cache.put(uri, bmp);
            main.post(() -> {
                if (uri.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    /** Drops the cached pictures (memory pressure); cards ask again when they are shown next. */
    void trim() {
        cache.evictAll();
    }

    void shutdown() {
        pool.shutdownNow();
        cache.evictAll();
    }

    private Bitmap fetch(String uri) {
        try {
            byte[] data = bytes(uri);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            o.inSampleSize = sampleSize(o.outWidth, targetWidth);
            // The width alone does not bound a very tall picture: keep every decode under about 1 MP (2 MB in
            // RGB_565), on a TV with under 1 GB.
            while ((long) (o.outWidth / o.inSampleSize) * (o.outHeight / o.inSampleSize) > MAX_PIXELS) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; cards are opaque
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "no picture for a shelf card: " + e);
            return null;
        }
    }

    /** The largest power of two that keeps the decoded width at or above target. */
    static int sampleSize(int width, int target) {
        int s = 1;
        while (target > 0 && width / (s * 2) >= target) s *= 2;
        return s;
    }

    private byte[] bytes(String uri) throws IOException {
        String scheme = Uri.parse(uri).getScheme();
        // https only: plain http would let anyone on the network swap the picture the decoder reads, and the
        // apps whose posters this shows publish https (a Google TV 14 probe found no http among 166 posters).
        if ("https".equals(scheme)) {
            HttpURLConnection con = (HttpURLConnection) new URL(uri).openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setUseCaches(false);
            try {
                if (con.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                try (InputStream in = con.getInputStream()) {
                    return readCapped(in);
                }
            } finally {
                con.disconnect();
            }
        }
        // No file://: that would read files with the launcher's own access, on another app's say-so.
        if ("content".equals(scheme) || "android.resource".equals(scheme)) {
            try (InputStream in = resolver.openInputStream(Uri.parse(uri))) {
                return in == null ? null : readCapped(in);
            }
        }
        return null;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;
        }
        return out.toByteArray();
    }
}
