package com.codex.tabletlivewallpaper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** Exported only from the instrumentation APK to provide deterministic image documents. */
public final class TestImageProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        String path = uri.getLastPathSegment() == null ? "valid" : uri.getLastPathSegment();
        if (path.startsWith("slow-")) {
            try { Thread.sleep(1_200L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        File file = new File(getContext().getCacheDir(), "provider-" + path + ".png");
        try {
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.MAGENTA);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded);
            bitmap.recycle();
            byte[] bytes = encoded.toByteArray();
            if (path.contains("corrupt")) corruptPngPixelPayload(bytes);
            if (path.startsWith("pipe-")) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                Thread writer = new Thread(() -> {
                    try (ParcelFileDescriptor.AutoCloseOutputStream output =
                                 new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        output.write(bytes);
                    } catch (Exception ignored) { }
                }, "test-image-pipe");
                writer.start();
                return pipe[0];
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
                output.getFD().sync();
            }
        } catch (Exception failure) {
            throw new java.io.FileNotFoundException(failure.toString());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }

    /** Keep the PNG signature/IHDR readable for a successful bounds pass, but invalidate the
        compressed IDAT bytes so a real pixel decode must fail. */
    private static void corruptPngPixelPayload(byte[] bytes) {
        for (int i = 4; i + 8 < bytes.length; i++) {
            if (bytes[i] == 'I' && bytes[i + 1] == 'D'
                    && bytes[i + 2] == 'A' && bytes[i + 3] == 'T') {
                int length = ((bytes[i - 4] & 0xff) << 24)
                        | ((bytes[i - 3] & 0xff) << 16)
                        | ((bytes[i - 2] & 0xff) << 8)
                        | (bytes[i - 1] & 0xff);
                int end = Math.min(bytes.length, i + 4 + length);
                for (int offset = i + 4; offset < end; offset++) bytes[offset] = 0;
                return;
            }
        }
        throw new IllegalStateException("Generated PNG did not contain IDAT");
    }
}
