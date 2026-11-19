package jp.mk.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MK Player Mobile — PC版 MK-PLYAR のUIと思想を継承したローカル音源プレーヤー。
 * 画面は [プレーヤー] [ライブラリ] [プレイリスト] の3領域に分離する。
 */
public class MainActivity extends Activity {
    private static final int REQ_FILES = 10;
    private static final int REQ_TREE = 11;
    private static final String[] METER_MODES = {"dBFS / Peak", "LUFS", "Average", "RMS", "Peak"};

    private static final int BG = 0xFF050505;
    private static final int PANEL = 0xFF080808;
    private static final int BORDER = 0xFFD8D8D8;
    private static final int TEXT = 0xFFF1F1F1;
    private static final int SUB = 0xFF999999;
    private static final int BTN_BG = 0xFF171717;
    private static final int SEL_BG = 0xFF333333;
    private static final int TAB_ON = 0xFF164B48;

    private final ArrayList<Tracks.Track> library = new ArrayList<>();
    private final ArrayList<Tracks.Track> playlist = new ArrayList<>();
    private final HashMap<String, Tracks.Track> cache = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private PlaybackService service;
    private boolean bound;
    private boolean restored;
    private String shownUri = "";
    private int artGeneration = 0;
    private boolean seeking;

    private final String[] meterModes = {"LUFS", "LUFS"};
    private final int[] meterOffsets = {0, 0};
    private boolean meterSegment;
    private String meterSmoothing = "なめらか";
    private boolean meterLightweight;
    private TextView lightBtn;
    private static final String[] SMOOTHING_NAMES = {"なし", "弱", "標準", "なめらか", "ゆったり"};

    private TextView[] tabBtns;
    private View[] pages;
    private TextView titleTv, artistTv, albumTv, metaTv, artPlaceholder;
    private ImageView artImage;
    private MeterView meterView;
    private TextView elapsedTv, remainTv, totalTv, playBtn, volPctTv, statusTv;
    private SeekBar seekBar, volBar;
    private LinearLayout libList, plList;
    private EditText searchBox;
    private TextView libCountTv, plCountTv;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastSaveMs = 0;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshTransport();
            long now = System.currentTimeMillis();
            if (now - lastSaveMs > 5000) { lastSaveMs = now; saveSession(); }
            handler.postDelayed(this, 250);
        }
    };
    // メーターは 100ms 周期で値を取り込む（250ms だとバーの動きが段々になる）
    private final Runnable meterTicker = new Runnable() {
        @Override public void run() {
            refreshMeters();
            handler.postDelayed(this, 100);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((PlaybackService.PlayerBinder) b).service();
            bound = true;
            service.setListener(new PlaybackService.Listener() {
                @Override public void changed() { refreshTransport(); renderLists(); }
                @Override public void failed(String s) {
                    Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show();
                }
                @Override public void trackChanged() { refreshTransport(); renderLists(); }
            });
            service.engine().setVolume(prefs.getInt("volume", 72) / 100f);
            if (!restored) {
                restored = true;
                restoreSession();
            }
            refreshTransport();
            renderLists();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            bound = false;
            service = null;
        }
    };

    // ---------------- lifecycle ----------------

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("mkplayer", 0);
        loadLists();
        loadMeterSettings();
        buildUi();
        switchTab(0);
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1); }
            catch (Exception ignored) { }
        }
        bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
        handler.post(ticker);
        handler.post(meterTicker);
        // ライブラリのタグを裏で順に読む
        io.execute(() -> {
            for (Tracks.Track t : new ArrayList<>(library)) {
                if (!t.loaded) Tracks.loadMeta(this, t);
            }
            runOnUiThread(this::renderLists);
        });
    }

    @Override protected void onPause() {
        saveSession();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(meterTicker);
        saveSession();
        if (bound) {
            try { service.setListener(null); } catch (Exception ignored) { }
            unbindService(connection);
        }
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------- UI helpers ----------------

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable rect(int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private LinearLayout panel() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rect(PANEL, BORDER, 0, 1.5f));
        l.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        l.setLayoutParams(p);
        return l;
    }

    private TextView button(String s, float sp) {
        TextView t = tv(s, sp, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rect(BTN_BG, 0xFF555555, 0, 1));
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        return t;
    }

    // ---------------- UI build ----------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = tv("Mk Player", 16, TEXT, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(tv("v1.0", 11, SUB, false));
        root.addView(header);

        // タブ（PC版の3領域分離をタブに再配置）
        LinearLayout tabs = new LinearLayout(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(8);
        tp.bottomMargin = dp(10);
        tabs.setLayoutParams(tp);
        String[] names = {"プレーヤー", "ライブラリ", "プレイリスト", "設定"};
        tabBtns = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int at = i;
            TextView b = tv(names[i], 14, TEXT, false);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(10), 0, dp(10));
            b.setOnClickListener(v -> switchTab(at));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) bp.leftMargin = dp(6);
            tabs.addView(b, bp);
            tabBtns[i] = b;
        }
        root.addView(tabs);

        FrameLayout frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        pages = new View[]{buildPlayerPage(), buildLibraryPage(), buildPlaylistPage(), buildSettingsPage()};
        for (View p : pages) frame.addView(p);
        setContentView(root);
    }

    private void switchTab(int index) {
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            tabBtns[i].setBackground(rect(i == index ? TAB_ON : BTN_BG,
                    i == index ? 0xFF65DCCC : 0xFF555555, 0, 1));
        }
        if (index == 0 && meterView != null) {
            meterView.setActive(service != null && service.isPlaying() && !meterLightweight);
        }
    }

    private View buildPlayerPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);

        // --- 曲情報パネル（本家準拠: 左に曲名/アーティスト/アルバム/ファイル情報、右に Album Art） ---
        LinearLayout info = panel();
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        titleTv = tv("曲名", 22, TEXT, true);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        leftCol.addView(titleTv);
        artistTv = tv("アーティスト", 15, SUB, false);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(2);
        leftCol.addView(artistTv, ap);
        albumTv = tv("アルバム", 14, 0xFFAAAAAA, false);
        leftCol.addView(albumTv, ap);
        metaTv = tv("", 11, SUB, false);
        metaTv.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(10);
        leftCol.addView(metaTv, mp);
        infoRow.addView(leftCol, new LinearLayout.LayoutParams(0, -2, 1));
        // Album Art: 枠は常に表示（PC版の思想）。右側に正方形で配置
        FrameLayout artFrame = new FrameLayout(this);
        artFrame.setBackground(rect(0xFF000000, 0xFFDDDDDD, 0, 1.5f));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(118), dp(118));
        fp.leftMargin = dp(10);
        artFrame.setLayoutParams(fp);
        artPlaceholder = tv("アルバムアート", 11, SUB, false);
        artPlaceholder.setGravity(Gravity.CENTER);
        artFrame.addView(artPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        artImage = new ImageView(this);
        artImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artFrame.addView(artImage, new FrameLayout.LayoutParams(-1, -1));
        infoRow.addView(artFrame);
        info.addView(infoRow);
        col.addView(info);

        // --- レベルメーターパネル ---
        LinearLayout meterPanel = panel();
        LinearLayout mHead = new LinearLayout(this);
        mHead.setGravity(Gravity.CENTER_VERTICAL);
        mHead.addView(tv("レベルメーター", 13, 0xFFBBBBBB, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        lightBtn = button("軽量", 12);
        lightBtn.setOnClickListener(v -> toggleLightweight());
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-2, -2);
        lbp.rightMargin = dp(8);
        lightBtn.setLayoutParams(lbp);
        mHead.addView(lightBtn);
        TextView gear = button("⚙", 15);
        gear.setOnClickListener(v -> showMeterSettings());
        mHead.addView(gear);
        meterPanel.addView(mHead);
        meterView = new MeterView(this);
        meterView.configure(0, meterModes[0], meterOffsets[0]);
        meterView.configure(1, meterModes[1], meterOffsets[1]);
        meterView.setSegmentStyle(meterSegment);
        meterView.setSmoothing(meterSmoothing);
        applyLightBtnStyle();
        LinearLayout.LayoutParams mvp = new LinearLayout.LayoutParams(-1, -2);
        mvp.topMargin = dp(6);
        meterPanel.addView(meterView, mvp);
        // 配置は後段で: 曲情報 → 操作 → レベルメーター（一番下）

        // --- トランスポートパネル ---
        LinearLayout tr = panel();
        LinearLayout times = new LinearLayout(this);
        elapsedTv = tv("00:00", 16, TEXT, false);
        times.addView(elapsedTv);
        remainTv = tv("-00:00", 16, TEXT, false);
        remainTv.setGravity(Gravity.CENTER);
        times.addView(remainTv, new LinearLayout.LayoutParams(0, -2, 1));
        totalTv = tv("00:00", 16, TEXT, false);
        times.addView(totalTv);
        tr.addView(times);
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFB9B9B9));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFB9B9B9));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (user && service != null) {
                    double dur = currentDurationSec();
                    if (dur > 0) {
                        long posMs = (long) (dur * 1000 * p / 1000);
                        elapsedTv.setText(Tracks.fmtTime(posMs));
                        remainTv.setText(Tracks.fmtTime((long) (dur * 1000) - posMs, true));
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar b) {
                seeking = false;
                if (service != null) {
                    double dur = currentDurationSec();
                    if (dur > 0) {
                        service.engine().seekTo(dur * b.getProgress() / 1000.0);
                        service.syncSessionState();
                    }
                }
            }
        });
        tr.addView(seekBar);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(6), 0, dp(6));
        TextView prev = tv("|◀", 24, TEXT, true);
        prev.setGravity(Gravity.CENTER);
        prev.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(54)));
        prev.setOnClickListener(v -> { if (service != null) service.previous(); });
        playBtn = tv("▶", 36, TEXT, true);
        playBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(80), dp(68));
        pp.leftMargin = dp(20);
        pp.rightMargin = dp(20);
        playBtn.setLayoutParams(pp);
        playBtn.setOnClickListener(v -> togglePlay());
        TextView next = tv("▶|", 24, TEXT, true);
        next.setGravity(Gravity.CENTER);
        next.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(54)));
        next.setOnClickListener(v -> { if (service != null) service.next(); });
        controls.addView(prev);
        controls.addView(playBtn);
        controls.addView(next);
        tr.addView(controls);
        LinearLayout volRow = new LinearLayout(this);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.addView(tv("音量", 13, TEXT, false));
        volBar = new SeekBar(this);
        volBar.setMax(100);
        volBar.setProgress(prefs.getInt("volume", 72));
        volBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFB9B9B9));
        volBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFB9B9B9));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(dp(170), -2);
        vp.leftMargin = dp(10);
        volBar.setLayoutParams(vp);
        volPctTv = tv(volBar.getProgress() + "%", 13, TEXT, false);
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                volPctTv.setText(p + "%");
                if (user) {
                    if (service != null) service.engine().setVolume(p / 100f);
                    prefs.edit().putInt("volume", p).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        volRow.addView(volBar);
        volRow.addView(volPctTv);
        tr.addView(volRow);
        col.addView(tr);
        // MK指示: レベルメーターは一番下
        col.addView(meterPanel);

        statusTv = tv("音源フォルダを開いてライブラリに追加してください", 11, SUB, false);
        col.addView(statusTv);
        return scroll;
    }

    private View buildLibraryPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("音源リスト", 20, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        libCountTv = tv("0 曲", 12, SUB, false);
        head.addView(libCountTv);
        TextView libOps = button("操作", 14);
        LinearLayout.LayoutParams lop = new LinearLayout.LayoutParams(-2, -2);
        lop.leftMargin = dp(10);
        libOps.setLayoutParams(lop);
        libOps.setOnClickListener(v -> showLibraryOps());
        head.addView(libOps);
        TextView add = button("＋ 開く", 14);
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(-2, -2);
        abp.leftMargin = dp(10);
        add.setLayoutParams(abp);
        add.setOnClickListener(v -> showOpenSource());
        head.addView(add);
        col.addView(head);
        searchBox = new EditText(this);
        searchBox.setHint("⌕  曲名・アーティスト・ファイル名を検索");
        searchBox.setHintTextColor(0xFF777777);
        searchBox.setTextColor(TEXT);
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setBackground(rect(0xFF171717, 0xFF555555, 0, 1));
        searchBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2);
        sbp.topMargin = dp(8);
        sbp.bottomMargin = dp(8);
        searchBox.setLayoutParams(sbp);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { renderLibrary(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        col.addView(searchBox);
        ScrollView scroll = new ScrollView(this);
        libList = new LinearLayout(this);
        libList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(libList);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return col;
    }

    private View buildPlaylistPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv("プレイリスト", 20, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        plCountTv = tv("0 曲", 12, SUB, false);
        head.addView(plCountTv);
        TextView ops = button("操作", 14);
        LinearLayout.LayoutParams obp = new LinearLayout.LayoutParams(-2, -2);
        obp.leftMargin = dp(10);
        ops.setLayoutParams(obp);
        ops.setOnClickListener(v -> showPlaylistOps());
        head.addView(ops);
        col.addView(head);
        TextView hint = tv("ライブラリで曲を長押しするとプレイリストに追加できます", 11, SUB, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(6);
        hp.bottomMargin = dp(6);
        col.addView(hint, hp);
        ScrollView scroll = new ScrollView(this);
        plList = new LinearLayout(this);
        plList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(plList);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return col;
    }

    private TextView settingsButton(String s) {
        TextView b = button(s, 14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    private TextView settingsSection(String s) {
        TextView t = tv(s, 15, TEXT, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        p.bottomMargin = dp(8);
        t.setLayoutParams(p);
        return t;
    }

    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        col.addView(tv("設定", 20, TEXT, true));

        col.addView(settingsSection("レベルメーター"));
        TextView meterCfg = settingsButton("メーター設定（方式・オフセット・滑らかさ・表示）");
        meterCfg.setOnClickListener(v -> showMeterSettings());
        col.addView(meterCfg);
        TextView meterReset = settingsButton("メーターをリセット");
        meterReset.setOnClickListener(v -> {
            if (meterView != null) meterView.clear();
            Toast.makeText(this, "メーターをリセットしました", Toast.LENGTH_SHORT).show();
        });
        col.addView(meterReset);

        col.addView(settingsSection("ライブラリ / プレイリスト"));
        TextView libClear = settingsButton("ライブラリを全削除");
        libClear.setOnClickListener(v -> confirmClearLibrary());
        col.addView(libClear);
        TextView plClear = settingsButton("プレイリストを空にする");
        plClear.setOnClickListener(v -> {
            playlist.clear();
            afterPlaylistEdit();
            Toast.makeText(this, "プレイリストを空にしました", Toast.LENGTH_SHORT).show();
        });
        col.addView(plClear);

        col.addView(settingsSection("再生"));
        TextView procReset = settingsButton("処理リセット（再生とメーターを初期化）");
        procReset.setOnClickListener(v -> {
            if (service != null) service.resetProcessing();
            shownUri = "__reset__";
            if (meterView != null) meterView.clear();
            refreshTransport();
            renderLists();
            Toast.makeText(this, "再生処理をリセットしました（ライブラリと設定は保持）",
                    Toast.LENGTH_SHORT).show();
        });
        col.addView(procReset);

        col.addView(settingsSection("情報"));
        col.addView(tv("Mk Player v1.0\nパッケージ: jp.mk.player\n"
                + "PC版 MK-PLYAR（仕様v0.6）のスマホ版。GEQ / VUメーターは無し。",
                12, SUB, false));
        return scroll;
    }

    // ---------------- lists ----------------

    private Tracks.Track trackFor(Uri u) {
        String key = u.toString();
        Tracks.Track t = cache.get(key);
        if (t == null) {
            t = new Tracks.Track(u);
            cache.put(key, t);
        }
        return t;
    }

    private List<Tracks.Track> visibleLibrary() {
        String q = searchBox == null ? "" : searchBox.getText().toString().trim().toLowerCase(Locale.US);
        ArrayList<Tracks.Track> out = new ArrayList<>();
        for (Tracks.Track t : library) {
            if (q.isEmpty()
                    || t.displayTitle().toLowerCase(Locale.US).contains(q)
                    || t.artist.toLowerCase(Locale.US).contains(q)
                    || t.fileName.toLowerCase(Locale.US).contains(q)) {
                out.add(t);
            }
        }
        return out;
    }

    private void renderLists() {
        renderLibrary();
        renderPlaylist();
    }

    private void renderLibrary() {
        if (libList == null) return;
        libList.removeAllViews();
        List<Tracks.Track> visible = visibleLibrary();
        libCountTv.setText(library.size() + " 曲");
        Uri cur = service == null ? null : service.currentUri();
        for (Tracks.Track t : visible) {
            libList.addView(makeRow(t, t.uri.equals(cur), v -> playFromLibrary(t),
                    v -> { showLibraryMenu(t); return true; }));
        }
        if (!library.isEmpty()) statusTv.setText("");
    }

    private void renderPlaylist() {
        if (plList == null) return;
        plList.removeAllViews();
        plCountTv.setText(playlist.size() + " 曲");
        Uri cur = service == null ? null : service.currentUri();
        for (int i = 0; i < playlist.size(); i++) {
            final Tracks.Track t = playlist.get(i);
            plList.addView(makeRow(t, t.uri.equals(cur), v -> playFromPlaylist(t),
                    v -> { showPlaylistRowMenu(t); return true; }));
        }
    }

    private View makeRow(Tracks.Track t, boolean current, View.OnClickListener click,
                         View.OnLongClickListener longClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(current ? SEL_BG : Color.TRANSPARENT);
        LinearLayout line1 = new LinearLayout(this);
        TextView title = tv(t.displayTitle(), 15, TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        line1.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView dur = tv(t.durationMs > 0 ? Tracks.fmtTime(t.durationMs) : "--:--", 13, SUB, false);
        line1.addView(dur);
        row.addView(line1);
        if (!t.artist.isEmpty()) {
            TextView artist = tv(t.artist, 12, SUB, false);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(artist);
        }
        row.setOnClickListener(click);
        row.setOnLongClickListener(longClick);
        return row;
    }

    // ---------------- playback control ----------------

    private void togglePlay() {
        if (service == null) return;
        if (service.getCurrent() < 0) {
            if (!library.isEmpty()) playFromLibrary(library.get(0));
            else showOpenSource();
            return;
        }
        service.toggle();
        refreshTransport();
    }

    private void playFromLibrary(Tracks.Track t) {
        if (service == null) return;
        List<Tracks.Track> visible = visibleLibrary();
        ArrayList<Uri> q = new ArrayList<>();
        for (Tracks.Track x : visible) q.add(x.uri);
        service.setQueueAndPlay(q, Math.max(0, visible.indexOf(t)));
        switchTab(0);
    }

    private void playFromPlaylist(Tracks.Track t) {
        if (service == null) return;
        ArrayList<Uri> q = new ArrayList<>();
        for (Tracks.Track x : playlist) q.add(x.uri);
        service.setQueueAndPlay(q, Math.max(0, playlist.indexOf(t)));
        switchTab(0);
    }

    private double currentDurationSec() {
        if (service == null) return 0;
        double d = service.engine().getDurationSec();
        if (d > 0) return d;
        Uri cur = service.currentUri();
        Tracks.Track t = cur == null ? null : cache.get(cur.toString());
        return t == null ? 0 : t.durationMs / 1000.0;
    }

    private void refreshTransport() {
        if (service == null || titleTv == null) return;
        Uri cur = service.currentUri();
        boolean playing = service.isPlaying();
        playBtn.setText(playing ? "Ⅱ" : "▶");
        if (meterView != null) {
            meterView.setActive(playing && !meterLightweight
                    && pages[0].getVisibility() == View.VISIBLE);
        }
        double dur = currentDurationSec();
        double pos = service.engine().getPositionSec();
        if (!seeking && dur > 0) seekBar.setProgress((int) (pos * 1000 / dur));
        elapsedTv.setText(Tracks.fmtTime((long) (pos * 1000)));
        remainTv.setText(Tracks.fmtTime((long) Math.max(0, (dur - pos) * 1000), true));
        totalTv.setText(Tracks.fmtTime((long) (dur * 1000)));
        String key = cur == null ? "" : cur.toString();
        if (!key.equals(shownUri)) {
            shownUri = key;
            updateTrackInfo(cur);
        }
    }

    private void updateTrackInfo(Uri cur) {
        if (cur == null) {
            titleTv.setText("曲名");
            artistTv.setText("アーティスト");
            albumTv.setText("アルバム");
            metaTv.setText("");
            artImage.setImageBitmap(null);
            artPlaceholder.setVisibility(View.VISIBLE);
            return;
        }
        Tracks.Track t = trackFor(cur);
        final int gen = ++artGeneration;
        Runnable apply = () -> {
            if (gen != artGeneration) return;
            titleTv.setText(t.displayTitle());
            artistTv.setText(t.artist.isEmpty() ? "" : t.artist);
            albumTv.setText(t.album.isEmpty() ? "" : t.album);
            metaTv.setText(t.fileMeta());
            if (service != null) service.setMetadata(t.displayTitle(),
                    t.artist.isEmpty() ? "ローカル音源" : t.artist);
        };
        apply.run();
        io.execute(() -> {
            if (!t.loaded) {
                Tracks.loadMeta(this, t);
            }
            byte[] art = Tracks.loadArt(this, cur);
            runOnUiThread(() -> {
                if (gen != artGeneration) return;
                apply.run();
                renderLists();
                if (art != null && art.length > 0) {
                    artImage.setImageBitmap(BitmapFactory.decodeByteArray(art, 0, art.length));
                    artPlaceholder.setVisibility(View.GONE);
                } else {
                    artImage.setImageBitmap(null);
                    artPlaceholder.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void applyLightBtnStyle() {
        if (lightBtn == null) return;
        lightBtn.setBackground(rect(meterLightweight ? TAB_ON : BTN_BG,
                meterLightweight ? 0xFF65DCCC : 0xFF555555, 0, 1));
    }

    /** PC版の「軽量」: メーター解析表示を止めて負荷を抑える。 */
    private void toggleLightweight() {
        meterLightweight = !meterLightweight;
        applyLightBtnStyle();
        saveMeterSettings();
        if (meterView != null) {
            if (meterLightweight) {
                meterView.setActive(false);
                meterView.clear();
            } else {
                meterView.setActive(service != null && service.isPlaying());
            }
        }
    }

    private void refreshMeters() {
        if (service == null || meterView == null || meterLightweight) return;
        Meters m = service.engine().getMeters();
        if (m == null) return;
        double[][] db = new double[2][2];
        boolean[][] over = new boolean[2][2];
        for (int g = 0; g < 2; g++) {
            for (int ch = 0; ch < 2; ch++) {
                double v;
                switch (meterModes[g]) {
                    case "RMS": v = m.rmsDb(ch); break;
                    case "Average": v = m.avgDb(ch); break;
                    case "LUFS": v = m.lufsDb(ch); break;
                    default: v = m.peakDb(ch); break; // dBFS / Peak, Peak
                }
                if (v <= Meters.FLOOR + 1) v = -40;
                double withOffset = v + meterOffsets[g];
                db[g][ch] = withOffset;
                over[g][ch] = withOffset >= 0;
            }
        }
        meterView.setTargets(db, over);
    }

    // ---------------- dialogs ----------------

    private void showOpenSource() {
        new AlertDialog.Builder(this)
                .setTitle("ライブラリに追加")
                .setItems(new String[]{"ファイルを追加（複数選択可）", "フォルダを追加（中を全部）"},
                        (d, w) -> {
                            if (w == 0) {
                                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                i.setType("audio/*");
                                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                                startActivityForResult(i, REQ_FILES);
                            } else {
                                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                startActivityForResult(i, REQ_TREE);
                            }
                        })
                .show();
    }

    private void showLibraryOps() {
        new AlertDialog.Builder(this)
                .setTitle("ライブラリ操作")
                .setItems(new String[]{"ライブラリを全削除"}, (d, w) -> confirmClearLibrary())
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmClearLibrary() {
        new AlertDialog.Builder(this)
                .setTitle("ライブラリを全削除")
                .setMessage("ライブラリの全 " + library.size()
                        + " 曲を一覧から削除します。音源ファイル自体は消えません。")
                .setPositiveButton("全削除", (d, w) -> {
                    library.clear();
                    saveLists();
                    renderLibrary();
                    Toast.makeText(this, "ライブラリを全削除しました", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showLibraryMenu(Tracks.Track t) {
        new AlertDialog.Builder(this)
                .setTitle(t.displayTitle())
                .setItems(new String[]{"再生", "プレイリストに追加", "ライブラリから削除"},
                        (d, w) -> {
                            if (w == 0) playFromLibrary(t);
                            else if (w == 1) {
                                playlist.add(t);
                                saveLists();
                                renderPlaylist();
                                Toast.makeText(this, "プレイリストに追加しました", Toast.LENGTH_SHORT).show();
                            } else {
                                library.remove(t);
                                saveLists();
                                renderLibrary();
                            }
                        })
                .show();
    }

    private void showPlaylistRowMenu(Tracks.Track t) {
        int idx = playlist.indexOf(t);
        new AlertDialog.Builder(this)
                .setTitle(t.displayTitle())
                .setItems(new String[]{"再生", "上へ", "下へ", "プレイリストから削除"},
                        (d, w) -> {
                            if (w == 0) playFromPlaylist(t);
                            else if (w == 1 && idx > 0) {
                                playlist.remove(idx);
                                playlist.add(idx - 1, t);
                                afterPlaylistEdit();
                            } else if (w == 2 && idx >= 0 && idx < playlist.size() - 1) {
                                playlist.remove(idx);
                                playlist.add(idx + 1, t);
                                afterPlaylistEdit();
                            } else if (w == 3) {
                                playlist.remove(t);
                                afterPlaylistEdit();
                            }
                        })
                .show();
    }

    private void afterPlaylistEdit() {
        saveLists();
        renderPlaylist();
        if (service != null) {
            ArrayList<Uri> q = new ArrayList<>();
            for (Tracks.Track x : playlist) q.add(x.uri);
            // 再生中キューがプレイリスト由来かは問わず、現在の再生は維持する
            if (service.getQueue().containsAll(q) || q.contains(service.currentUri())) {
                service.replaceQueueSilently(q);
            }
        }
        saveSession();
    }

    private void showPlaylistOps() {
        new AlertDialog.Builder(this)
                .setTitle("プレイリスト操作")
                .setItems(new String[]{"プレイリストを保存", "プレイリストを読み込み", "プレイリストを空にする"},
                        (d, w) -> {
                            if (w == 0) showSavePlaylist();
                            else if (w == 1) showLoadPlaylist();
                            else {
                                playlist.clear();
                                afterPlaylistEdit();
                            }
                        })
                .show();
    }

    private void showSavePlaylist() {
        EditText input = new EditText(this);
        input.setHint("プレイリスト名");
        input.setTextColor(TEXT);
        new AlertDialog.Builder(this)
                .setTitle("プレイリストを保存")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    ArrayList<String> uris = new ArrayList<>();
                    for (Tracks.Track t : playlist) uris.add(t.uri.toString());
                    HashSet<String> names = new HashSet<>(
                            prefs.getStringSet("plnames", new HashSet<>()));
                    names.add(name);
                    prefs.edit()
                            .putString("pl:" + name, TextUtils.join("\n", uris))
                            .putStringSet("plnames", names)
                            .apply();
                    Toast.makeText(this, "保存しました: " + name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showLoadPlaylist() {
        ArrayList<String> names = new ArrayList<>(
                prefs.getStringSet("plnames", new HashSet<>()));
        if (names.isEmpty()) {
            Toast.makeText(this, "保存済みプレイリストはありません", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("プレイリストを読み込み")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    String saved = prefs.getString("pl:" + names.get(w), "");
                    playlist.clear();
                    for (String s : saved.split("\n")) {
                        if (!s.isEmpty()) playlist.add(trackFor(Uri.parse(s)));
                    }
                    afterPlaylistEdit();
                    io.execute(() -> {
                        for (Tracks.Track t : new ArrayList<>(playlist)) {
                            if (!t.loaded) Tracks.loadMeta(this, t);
                        }
                        runOnUiThread(this::renderLists);
                    });
                })
                .show();
    }

    private void showMeterSettings() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(10), dp(20), dp(6));
        final Spinner[] spinners = new Spinner[2];
        final TextView[] offsetLabels = new TextView[2];
        final int[] offsets = {meterOffsets[0], meterOffsets[1]};
        for (int g = 0; g < 2; g++) {
            final int at = g;
            col.addView(tv("Meter " + (g == 0 ? "A" : "B") + " 方式", 14, TEXT, true));
            Spinner sp = new Spinner(this);
            ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, METER_MODES);
            sp.setAdapter(ad);
            for (int i = 0; i < METER_MODES.length; i++) {
                if (METER_MODES[i].equals(meterModes[g])) sp.setSelection(i);
            }
            spinners[g] = sp;
            col.addView(sp);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(tv("オフセット", 13, TEXT, false),
                    new LinearLayout.LayoutParams(0, -2, 1));
            Button minus = new Button(this);
            minus.setText("−");
            TextView val = tv(String.format(Locale.US, "%+d dB", offsets[g]), 14, TEXT, false);
            val.setGravity(Gravity.CENTER);
            val.setLayoutParams(new LinearLayout.LayoutParams(dp(64), -2));
            Button plus = new Button(this);
            plus.setText("＋");
            minus.setOnClickListener(v -> {
                offsets[at] = Math.max(-20, offsets[at] - 1);
                val.setText(String.format(Locale.US, "%+d dB", offsets[at]));
            });
            plus.setOnClickListener(v -> {
                offsets[at] = Math.min(20, offsets[at] + 1);
                val.setText(String.format(Locale.US, "%+d dB", offsets[at]));
            });
            offsetLabels[g] = val;
            row.addView(minus);
            row.addView(val);
            row.addView(plus);
            col.addView(row);
        }
        col.addView(tv("バー表示", 14, TEXT, true));
        Spinner style = new Spinner(this);
        style.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"連続LED", "セグメントLED"}));
        style.setSelection(meterSegment ? 1 : 0);
        col.addView(style);
        col.addView(tv("バーの応答（滑らかさ）", 14, TEXT, true));
        Spinner smoothing = new Spinner(this);
        smoothing.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                SMOOTHING_NAMES));
        for (int i = 0; i < SMOOTHING_NAMES.length; i++) {
            if (SMOOTHING_NAMES[i].equals(meterSmoothing)) smoothing.setSelection(i);
        }
        col.addView(smoothing);
        new AlertDialog.Builder(this)
                .setTitle("レベルメーター設定")
                .setView(col)
                .setPositiveButton("OK", (d, w) -> {
                    for (int g = 0; g < 2; g++) {
                        meterModes[g] = METER_MODES[spinners[g].getSelectedItemPosition()];
                        meterOffsets[g] = offsets[g];
                        meterView.configure(g, meterModes[g], meterOffsets[g]);
                    }
                    meterSegment = style.getSelectedItemPosition() == 1;
                    meterView.setSegmentStyle(meterSegment);
                    meterSmoothing = SMOOTHING_NAMES[smoothing.getSelectedItemPosition()];
                    meterView.setSmoothing(meterSmoothing);
                    saveMeterSettings();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ---------------- file picking ----------------

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_FILES) {
            ArrayList<Uri> added = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    added.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                added.add(data.getData());
            }
            for (Uri u : added) {
                try {
                    getContentResolver().takePersistableUriPermission(u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
            }
            addToLibrary(added);
        } else if (req == REQ_TREE && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            statusTv.setText("フォルダを走査しています…");
            io.execute(() -> {
                List<Uri> found = Tracks.scanTree(this, tree);
                runOnUiThread(() -> addToLibrary(found));
            });
        }
    }

    private void addToLibrary(List<Uri> uris) {
        HashSet<String> existing = new HashSet<>();
        for (Tracks.Track t : library) existing.add(t.uri.toString());
        ArrayList<Tracks.Track> fresh = new ArrayList<>();
        for (Uri u : uris) {
            if (existing.add(u.toString())) {
                Tracks.Track t = trackFor(u);
                library.add(t);
                fresh.add(t);
            }
        }
        saveLists();
        renderLibrary();
        if (!fresh.isEmpty()) {
            Toast.makeText(this, fresh.size() + " 曲をライブラリに追加しました",
                    Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                for (Tracks.Track t : fresh) Tracks.loadMeta(this, t);
                runOnUiThread(this::renderLists);
            });
        }
    }

    // ---------------- persistence ----------------

    private String joinUris(List<Tracks.Track> list) {
        ArrayList<String> ss = new ArrayList<>();
        for (Tracks.Track t : list) ss.add(t.uri.toString());
        return TextUtils.join("\n", ss);
    }

    private void saveLists() {
        prefs.edit()
                .putString("library", joinUris(library))
                .putString("playlist", joinUris(playlist))
                .apply();
    }

    private void loadLists() {
        String lib = prefs.getString("library", "");
        for (String s : lib.split("\n")) {
            if (!s.isEmpty()) library.add(trackFor(Uri.parse(s)));
        }
        String pl = prefs.getString("playlist", "");
        for (String s : pl.split("\n")) {
            if (!s.isEmpty()) playlist.add(trackFor(Uri.parse(s)));
        }
    }

    private void saveMeterSettings() {
        prefs.edit()
                .putString("meter_mode_a", meterModes[0])
                .putString("meter_mode_b", meterModes[1])
                .putInt("meter_off_a", meterOffsets[0])
                .putInt("meter_off_b", meterOffsets[1])
                .putBoolean("meter_segment", meterSegment)
                .putString("meter_smoothing", meterSmoothing)
                .putBoolean("meter_light", meterLightweight)
                .apply();
    }

    private void loadMeterSettings() {
        meterModes[0] = prefs.getString("meter_mode_a", "LUFS");
        meterModes[1] = prefs.getString("meter_mode_b", "LUFS");
        meterOffsets[0] = prefs.getInt("meter_off_a", 0);
        meterOffsets[1] = prefs.getInt("meter_off_b", 0);
        meterSegment = prefs.getBoolean("meter_segment", false);
        meterSmoothing = prefs.getString("meter_smoothing", "なめらか");
        meterLightweight = prefs.getBoolean("meter_light", false);
    }

    private void saveSession() {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        if (service != null && service.getCurrent() >= 0) {
            ArrayList<String> ss = new ArrayList<>();
            for (Uri u : service.getQueue()) ss.add(u.toString());
            e.putString("queue", TextUtils.join("\n", ss));
            e.putInt("queue_index", service.getCurrent());
            e.putLong("queue_pos_ms", (long) (service.engine().getPositionSec() * 1000));
        }
        e.apply();
    }

    private void restoreSession() {
        String q = prefs.getString("queue", "");
        if (q.isEmpty() || service == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        for (String s : q.split("\n")) {
            if (!s.isEmpty()) {
                uris.add(Uri.parse(s));
                trackFor(Uri.parse(s));
            }
        }
        int idx = prefs.getInt("queue_index", -1);
        long posMs = prefs.getLong("queue_pos_ms", 0);
        if (!uris.isEmpty() && idx >= 0 && idx < uris.size()) {
            service.restore(uris, idx, posMs / 1000.0, prefs.getInt("volume", 72) / 100f);
        }
    }
}
