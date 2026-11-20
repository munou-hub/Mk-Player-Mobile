package jp.mk.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 再生エンジン。MediaExtractor + MediaCodec でデコードし AudioTrack で再生。
 * デコードした PCM をそのまま Meters に流すので、レベルメーターは実測値になる
 * （PC版 MK-PLYAR が PCM 出力から計測する方式の継承）。
 */
public class PlayerEngine {
    public interface Listener {
        void onEnded();
        void onError(String msg);
    }

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private volatile boolean playing = false;
    private volatile boolean stopFlag = false;
    private volatile boolean hasTrack = false;
    private Thread worker;
    private Uri uri;
    private double durationSec = 0;
    private int sampleRate = 0;
    private int channels = 0;
    private AudioTrack track;
    private volatile long startOffsetFrames = 0;
    private volatile float volume = 0.72f;
    private volatile float duckFactor = 1f;
    private volatile Meters meters;

    public PlayerEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) { listener = l; }

    public boolean isPlaying() { return playing; }
    public boolean hasTrack() { return hasTrack; }
    public double getDurationSec() { return durationSec; }
    public int getSampleRate() { return sampleRate; }
    public int getChannels() { return channels; }
    public Meters getMeters() { return meters; }
    public float getVolume() { return volume; }
    public void setVolume(float v) { volume = Math.max(0f, Math.min(1f, v)); }

    /** オーディオフォーカスのダッキング用（通話・通知音などに一時的に譲る）。 */
    public void setDuckFactor(float f) { duckFactor = Math.max(0f, Math.min(1f, f)); }

    public double getPositionSec() {
        AudioTrack t = track;
        if (t == null || sampleRate == 0) return 0;
        try {
            long head = t.getPlaybackHeadPosition() & 0xffffffffL;
            double pos = (startOffsetFrames + head) / (double) sampleRate;
            return durationSec > 0 ? Math.min(pos, durationSec) : pos;
        } catch (Exception e) {
            return 0;
        }
    }

    /** メタ情報だけ先に読む（再生はしない）。 */
    public void open(Uri u) {
        stopInternal();
        uri = u;
        durationSec = 0; sampleRate = 0; channels = 0;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, u, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationSec = fmt.getLong(MediaFormat.KEY_DURATION) / 1e6;
                    }
                    break;
                }
            }
        } catch (Exception e) {
            if (listener != null) listener.onError("ファイルを開けません: " + e.getMessage());
        } finally {
            ex.release();
        }
    }

    public void play() { playFrom(0); }

    public void playFrom(double sec) {
        stopInternal();
        if (uri == null) return;
        stopFlag = false;
        playing = true;
        hasTrack = true;
        final double startSec = Math.max(0, sec);
        worker = new Thread(() -> runPipeline(startSec), "mpm-player");
        worker.start();
    }

    public void seekTo(double sec) {
        boolean wasPlaying = playing;
        playFrom(sec);
        if (!wasPlaying) pause();
    }

    public void pause() {
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) { }
        }
        playing = false;
    }

    public void resume() {
        AudioTrack t = track;
        if (t != null) {
            try { t.play(); playing = true; } catch (Exception ignored) { }
        } else if (uri != null) {
            playFrom(0);
        }
    }

    public void stop() {
        stopInternal();
        hasTrack = false;
    }

    private void stopInternal() {
        stopFlag = true;
        playing = false;
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); t.flush(); t.stop(); } catch (Exception ignored) { }
            t.release();
        }
        if (worker != null) {
            try { worker.join(800); } catch (InterruptedException ignored) { }
            worker = null;
        }
        meters = null;
    }

    private void runPipeline(double startSec) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int trackIdx = -1;
            MediaFormat fmt = null;
            String mime = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    trackIdx = i; fmt = f; mime = m;
                    break;
                }
            }
            if (trackIdx < 0) throw new Exception("音声トラックがありません");
            ex.selectTrack(trackIdx);
            if (startSec > 0) {
                ex.seekTo((long) (startSec * 1e6), MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }
            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channels = Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                durationSec = fmt.getLong(MediaFormat.KEY_DURATION) / 1e6;
            }
            startOffsetFrames = (long) (startSec * sampleRate);
            meters = new Meters(sampleRate, channels);

            int chMask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_FLOAT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(chMask)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf * 2, sampleRate * channels * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            int pcmEnc = AudioFormat.ENCODING_PCM_16BIT;
            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();
            try {
                MediaFormat of0 = dec.getOutputFormat();
                if (of0.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    pcmEnc = of0.getInteger(MediaFormat.KEY_PCM_ENCODING);
                }
            } catch (Exception ignored) { }
            track.play();
            if (!playing) track.pause();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            float[] chunk = new float[8192 * 2];
            while (!stopFlag) {
                if (!playing) {
                    try { Thread.sleep(40); } catch (InterruptedException ignored) { }
                    continue;
                }
                if (!inputEos) {
                    int inIdx = dec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer in = dec.getInputBuffer(inIdx);
                        in.clear();
                        int size = ex.readSampleData(in, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = dec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 出力形式は変わった時だけ取り直す（毎バッファ取得は無駄）
                    try {
                        MediaFormat of = dec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEnc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                        }
                    } catch (Exception ignored) { }
                } else if (outIdx >= 0) {
                    ByteBuffer out = dec.getOutputBuffer(outIdx);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0 && track != null) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        ByteBuffer le = out.order(ByteOrder.LITTLE_ENDIAN);
                        int frames;
                        int need;
                        if (pcmEnc == AudioFormat.ENCODING_PCM_FLOAT) {
                            frames = info.size / 4 / channels;
                            need = frames * channels;
                            if (chunk.length < need) chunk = new float[need];
                            for (int i = 0; i < need; i++) chunk[i] = le.getFloat(info.offset + i * 4);
                        } else if (pcmEnc == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                            frames = info.size / 3 / channels;
                            need = frames * channels;
                            if (chunk.length < need) chunk = new float[need];
                            for (int i = 0; i < need; i++) {
                                int off = info.offset + i * 3;
                                int v = (le.get(off) & 0xff) | ((le.get(off + 1) & 0xff) << 8)
                                        | (le.get(off + 2) << 16);
                                chunk[i] = v / 8388608f;
                            }
                        } else {
                            frames = info.size / 2 / channels;
                            need = frames * channels;
                            if (chunk.length < need) chunk = new float[need];
                            for (int i = 0; i < need; i++) {
                                int v = (short) ((le.get(info.offset + i * 2) & 0xff)
                                        | (le.get(info.offset + i * 2 + 1) << 8));
                                chunk[i] = v / 32768f;
                            }
                        }
                        float g = volume * duckFactor;
                        if (g != 1f) {
                            for (int i = 0; i < need; i++) chunk[i] *= g;
                        }
                        Meters m = meters;
                        if (m != null) m.process(chunk, frames);
                        AudioTrack t = track;
                        if (t != null) t.write(chunk, 0, need, AudioTrack.WRITE_BLOCKING);
                    }
                    dec.releaseOutputBuffer(outIdx, false);
                    if (eos) break;
                }
            }
            boolean ended = !stopFlag;
            if (ended) {
                playing = false;
                mainHandler.post(() -> { if (listener != null) listener.onEnded(); });
            }
        } catch (Exception e) {
            if (!stopFlag) {
                playing = false;
                mainHandler.post(() -> { if (listener != null) listener.onError(e.getMessage()); });
            }
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (Exception ignored) { }
                dec.release();
            }
            ex.release();
        }
    }
}
