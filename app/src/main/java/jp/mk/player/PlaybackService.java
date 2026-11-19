package jp.mk.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;

/** 再生を担うフォアグラウンドサービス。キューと現在曲を持ち、通知操作も受ける。 */
public class PlaybackService extends Service {
    public static final String ACTION = "jp.mk.player.ACTION";
    public static final String PLAY = "play", PAUSE = "pause", NEXT = "next", PREV = "prev";

    public interface Listener {
        void changed();
        void failed(String message);
        void trackChanged();
    }

    public final class PlayerBinder extends Binder {
        public PlaybackService service() { return PlaybackService.this; }
    }

    private final IBinder binder = new PlayerBinder();
    private final ArrayList<Uri> queue = new ArrayList<>();
    private PlayerEngine engine;
    private int current = -1;
    private Listener listener;
    private String title = "Mk Player";
    private String artist = "ローカル音源";
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean focusHeld = false;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (engine != null && engine.isPlaying()) {
                engine.pause();
                updateNotification(false);
                notifyChanged();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        engine = new PlayerEngine(this);
        engine.setListener(new PlayerEngine.Listener() {
            @Override public void onEnded() {
                if (current >= 0 && current < queue.size() - 1) {
                    playIndex(current + 1);
                } else {
                    updateNotification(false);
                    notifyChanged();
                }
            }
            @Override public void onError(String msg) {
                if (listener != null) listener.failed(msg);
                notifyChanged();
            }
        });
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION.equals(intent.getAction())) {
            String command = intent.getStringExtra("command");
            if (NEXT.equals(command)) next();
            else if (PREV.equals(command)) previous();
            else if (PAUSE.equals(command)) pause();
            else if (PLAY.equals(command)) resume();
        }
        return START_NOT_STICKY;
    }

    public void setListener(Listener l) { listener = l; }
    public PlayerEngine engine() { return engine; }
    public ArrayList<Uri> getQueue() { return queue; }
    public int getCurrent() { return current; }
    public boolean isPlaying() { return engine != null && engine.isPlaying(); }
    public Uri currentUri() {
        return current >= 0 && current < queue.size() ? queue.get(current) : null;
    }

    public void setQueueAndPlay(ArrayList<Uri> tracks, int start) {
        queue.clear();
        queue.addAll(tracks);
        if (queue.isEmpty()) { current = -1; return; }
        playIndex(Math.max(0, Math.min(start, queue.size() - 1)));
    }

    /** 再生を止めずにキューだけ差し替える（プレイリスト編集時の追随用）。 */
    public void replaceQueueSilently(ArrayList<Uri> tracks) {
        Uri active = currentUri();
        queue.clear();
        queue.addAll(tracks);
        current = active == null ? -1 : queue.indexOf(active);
    }

    public void playIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        current = index;
        requestFocus();
        engine.open(queue.get(index));
        engine.play();
        startForeground(1, notification(true));
        notifyChanged();
        if (listener != null) listener.trackChanged();
    }

    /** 起動時の状態復元用: 位置まで合わせて一時停止状態で読み込む。 */
    public void restore(ArrayList<Uri> tracks, int index, double positionSec, float volume) {
        queue.clear();
        queue.addAll(tracks);
        engine.setVolume(volume);
        if (index < 0 || index >= queue.size()) { current = -1; return; }
        current = index;
        engine.open(queue.get(index));
        if (positionSec > 0) {
            engine.playFrom(positionSec);
            engine.pause();
        }
        notifyChanged();
        if (listener != null) listener.trackChanged();
    }

    public void toggle() { if (isPlaying()) pause(); else resume(); }

    public void pause() {
        if (engine != null) engine.pause();
        updateNotification(false);
        notifyChanged();
    }

    public void resume() {
        if (engine == null) return;
        if (!engine.hasTrack()) {
            if (!queue.isEmpty()) playIndex(Math.max(0, current));
            return;
        }
        requestFocus();
        engine.resume();
        startForeground(1, notification(true));
        notifyChanged();
    }

    public void next() {
        if (!queue.isEmpty()) playIndex((current + 1) % queue.size());
    }

    public void previous() {
        if (engine != null && engine.getPositionSec() > 3) {
            engine.seekTo(0);
            return;
        }
        if (!queue.isEmpty()) playIndex(current <= 0 ? queue.size() - 1 : current - 1);
    }

    /** PC版の「処理リセット」: 再生処理とキューを初期化する（ライブラリ・設定は保持）。 */
    public void resetProcessing() {
        if (engine != null) engine.stop();
        queue.clear();
        current = -1;
        try { stopForeground(true); } catch (Exception ignored) { }
        notifyChanged();
    }

    public void setMetadata(String t, String a) {
        title = t;
        artist = a;
        updateNotification(isPlaying());
    }

    private void requestFocus() {
        if (audioManager == null || focusHeld) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            focusHeld = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void notifyChanged() { if (listener != null) listener.changed(); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel("playback", "再生",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private PendingIntent command(String command, int request) {
        Intent i = new Intent(this, PlaybackService.class).setAction(ACTION).putExtra("command", command);
        return PendingIntent.getService(this, request, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification(boolean playing) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "playback") : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title).setContentText(artist).setContentIntent(content)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "前へ", command(PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "一時停止" : "再生", command(playing ? PAUSE : PLAY, 2))
                .addAction(android.R.drawable.ic_media_next, "次へ", command(NEXT, 3))
                .build();
    }

    private void updateNotification(boolean playing) {
        if (engine != null && engine.hasTrack()) {
            try {
                getSystemService(NotificationManager.class).notify(1, notification(playing));
            } catch (Exception ignored) { }
        }
    }

    @Override public void onDestroy() {
        if (engine != null) engine.stop();
        if (audioManager != null && focusHeld && Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        super.onDestroy();
    }
}
