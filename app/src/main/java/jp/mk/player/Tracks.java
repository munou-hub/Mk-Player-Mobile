package jp.mk.player;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 1曲分の情報。タグが無い項目はファイル情報でフォールバックする（PC版の思想）。 */
public class Tracks {
    public static class Track {
        public final Uri uri;
        public String title = "";
        public String artist = "";
        public String album = "";
        public String fileName = "";
        public long durationMs = 0;
        public String mime = "";
        public int sampleRate = 0;
        public int bitrate = 0; // bps
        public boolean loaded = false;

        public Track(Uri uri) {
            this.uri = uri;
            this.fileName = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
            this.title = this.fileName;
        }

        public String displayTitle() {
            return title == null || title.isEmpty() ? fileName : title;
        }

        /** PC版の file_meta 形式: ".flac    1411 Kbps    N/A BIT    44,100 Hz    FLAC" */
        public String fileMeta() {
            String ext = "";
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) ext = fileName.substring(dot).toLowerCase(Locale.US);
            String br = bitrate > 0 ? (bitrate / 1000) + " Kbps" : "N/A Kbps";
            String bits = "N/A BIT";
            String rate = sampleRate > 0
                    ? String.format(Locale.US, "%,d Hz", sampleRate) : "N/A Hz";
            String codec = ext.length() > 1 ? ext.substring(1).toUpperCase(Locale.US) : "";
            if (mime != null && mime.contains("/")) {
                String sub = mime.substring(mime.indexOf('/') + 1).toUpperCase(Locale.US);
                if (!sub.isEmpty()) codec = sub;
            }
            return String.join("    ", ext, br, bits, rate, codec);
        }
    }

    public static String displayName(Context ctx, Uri u) {
        try (Cursor c = ctx.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int x = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (x >= 0) {
                    String s = c.getString(x);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) { }
        String seg = u.getLastPathSegment();
        return seg == null ? "" : seg;
    }

    /** タグ・長さ・形式を読む。重いので背景スレッドから呼ぶこと。 */
    public static void loadMeta(Context ctx, Track t) {
        t.fileName = displayName(ctx, t.uri);
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(ctx, t.uri);
            String title = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (artist == null) artist = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            String album = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String dur = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String mime = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            String br = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            t.title = title == null || title.isEmpty() ? t.fileName : title;
            t.artist = artist == null ? "" : artist;
            t.album = album == null ? "" : album;
            if (dur != null) t.durationMs = Long.parseLong(dur);
            t.mime = mime == null ? "" : mime;
            if (br != null) t.bitrate = Integer.parseInt(br);
            try {
                String sr = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                if (sr != null) t.sampleRate = Integer.parseInt(sr);
            } catch (Exception ignored) { }
        } catch (Exception ignored) {
            if (t.title == null || t.title.isEmpty()) t.title = t.fileName;
        } finally {
            try { m.release(); } catch (Exception ignored) { }
        }
        t.loaded = true;
    }

    /** 埋め込み画像を最大 maxPx px に収まるようサンプルデコードする（原寸デコードは重い）。 */
    public static android.graphics.Bitmap decodeArt(byte[] pic, int maxPx) {
        if (pic == null || pic.length == 0) return null;
        android.graphics.BitmapFactory.Options bounds =
                new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length, bounds);
        int sample = 1;
        int w = bounds.outWidth, h = bounds.outHeight;
        while (w / (sample * 2) >= maxPx && h / (sample * 2) >= maxPx) sample *= 2;
        android.graphics.BitmapFactory.Options opt =
                new android.graphics.BitmapFactory.Options();
        opt.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length, opt);
    }

    public static byte[] loadArt(Context ctx, Uri u) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(ctx, u);
            return m.getEmbeddedPicture();
        } catch (Exception ignored) {
            return null;
        } finally {
            try { m.release(); } catch (Exception ignored) { }
        }
    }

    private static final String[] AUDIO_EXT = {
            ".mp3", ".flac", ".wav", ".ogg", ".opus", ".m4a", ".aac", ".aif", ".aiff", ".wma"
    };

    public static boolean isAudioName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        for (String e : AUDIO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    /** フォルダツリーを再帰走査して音声ファイルの Uri を集める。背景スレッド用。 */
    public static List<Uri> scanTree(Context ctx, Uri treeUri) {
        List<Uri> out = new ArrayList<>();
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        scanDir(ctx, treeUri, treeId, out, 0);
        return out;
    }

    private static void scanDir(Context ctx, Uri treeUri, String docId, List<Uri> out, int depth) {
        if (depth > 8 || out.size() > 2000) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        try (Cursor c = ctx.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanDir(ctx, treeUri, id, out, depth + 1);
                } else if ((mime != null && mime.startsWith("audio/")) || isAudioName(name)) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id));
                }
            }
        } catch (Exception ignored) { }
    }

    public static String fmtTime(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    public static String fmtTime(long ms, boolean negative) {
        return (negative ? "-" : "") + fmtTime(ms);
    }
}
