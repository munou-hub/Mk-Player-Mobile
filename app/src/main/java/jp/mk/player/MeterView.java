package jp.mk.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * デュアルレベルメーター（PC版 MK-PLYAR の MeterPanel を横向きに再配置）。
 * Meter A / B の2セット × L/R。各セットは独立した方式・オフセットを持つ。
 * 帯域色・ピークホールド線・PEAKランプ・-40〜0dBレンジは PC版と同一。
 */
public class MeterView extends View {
    // PC版と同一の帯域色: {low, high, 点灯色, 消灯色}
    private static final int[][] BANDS = {
            {-40, -36, 0xFF007A35, 0xFF09271A},
            {-36, -20, 0xFF00F052, 0xFF0B321E},
            {-20, -6, 0xFFFFF500, 0xFF39340A},
            {-6, 0, 0xFFFF2020, 0xFF3B1111},
    };
    private static final double MIN_DB = -40.0, MAX_DB = 0.0;
    // PC版の応答設定と同一: なし/弱/標準/なめらか/ゆったり
    private static final java.util.Map<String, Double> SMOOTHING = new java.util.HashMap<>();
    static {
        SMOOTHING.put("なし", 1.0);
        SMOOTHING.put("弱", 0.58);
        SMOOTHING.put("標準", 0.32);
        SMOOTHING.put("なめらか", 0.18);
        SMOOTHING.put("ゆったり", 0.11);
    }
    private double smoothingAlpha = 0.18;

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final String[] modes = {"LUFS", "LUFS"};
    private final int[] offsets = {0, 0};
    private boolean segmentStyle = false;

    private final double[][] targetDb = {{-40, -40}, {-40, -40}};
    private final double[][] dispDb = {{-40, -40}, {-40, -40}};
    private final double[][] holdDb = {{-40, -40}, {-40, -40}};
    private final int[][] lampMs = {{0, 0}, {0, 0}};
    private long lastInputMs = 0;

    private boolean active = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            step();
            invalidate();
            if (active) handler.postDelayed(this, 50);
        }
    };

    public MeterView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF050505);
    }

    private float dp(float v) { return v * density; }

    public void configure(int index, String mode, int offset) {
        modes[index] = mode;
        offsets[index] = offset;
        invalidate();
    }

    public void setSegmentStyle(boolean segment) {
        segmentStyle = segment;
        invalidate();
    }

    public void setSmoothing(String name) {
        Double a = SMOOTHING.get(name);
        if (a != null) smoothingAlpha = a;
    }

    /** 計測値（オフセット適用後、クランプ前）を渡す。lamp は >0dB で true。 */
    public void setTargets(double[][] db, boolean[][] over) {
        for (int g = 0; g < 2; g++) {
            for (int ch = 0; ch < 2; ch++) {
                targetDb[g][ch] = db[g][ch];
                if (over[g][ch]) lampMs[g][ch] = 400;
            }
        }
        lastInputMs = System.currentTimeMillis();
    }

    public void clear() {
        for (int g = 0; g < 2; g++) {
            for (int ch = 0; ch < 2; ch++) {
                targetDb[g][ch] = -40; dispDb[g][ch] = -40; holdDb[g][ch] = -40; lampMs[g][ch] = 0;
            }
        }
        invalidate();
    }

    public void setActive(boolean on) {
        if (on == active) return;
        active = on;
        handler.removeCallbacks(tick);
        if (on) handler.post(tick);
    }

    private void step() {
        boolean stale = System.currentTimeMillis() - lastInputMs > 600;
        for (int g = 0; g < 2; g++) {
            for (int ch = 0; ch < 2; ch++) {
                double t = stale ? MIN_DB : Math.max(MIN_DB, Math.min(MAX_DB, targetDb[g][ch]));
                dispDb[g][ch] += (t - dispDb[g][ch]) * smoothingAlpha;
                holdDb[g][ch] = Math.max(dispDb[g][ch], holdDb[g][ch] - 0.18);
                lampMs[g][ch] = Math.max(0, lampMs[g][ch] - 50);
            }
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) dp(196);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth();
        float groupH = dp(94);
        float gap = dp(8);
        for (int g = 0; g < 2; g++) {
            float top = g * (groupH + gap);
            drawGroup(c, g, dp(2), top, w - dp(2), top + groupH);
        }
    }

    private void drawGroup(Canvas c, int g, float left, float top, float right, float bottom) {
        // パネル枠（PC版: 明色2px枠）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0xFFE7E7E7);
        c.drawRect(left, top, right, bottom, paint);
        paint.setStyle(Paint.Style.FILL);

        // 見出し: LEVEL A / B + 方式 + オフセット
        textPaint.setColor(0xFFE8E8E8);
        textPaint.setTextSize(dp(12));
        textPaint.setFakeBoldText(true);
        String head = "LEVEL " + (g == 0 ? "A" : "B");
        c.drawText(head, left + dp(10), top + dp(17), textPaint);
        float headW = textPaint.measureText(head);
        textPaint.setColor(0xFFAAAAAA);
        textPaint.setFakeBoldText(false);
        String modeText = modes[g].toUpperCase()
                + (offsets[g] != 0 ? String.format("  %+d dB", offsets[g]) : "");
        c.drawText(modeText, left + dp(10) + headW + dp(10), top + dp(17), textPaint);

        float barLeft = left + dp(26);
        float barRight = right - dp(76);
        float barH = dp(17);
        float rowY0 = top + dp(28);
        float rowGap = dp(30);
        for (int ch = 0; ch < 2; ch++) {
            float y = rowY0 + ch * rowGap;
            // チャンネル文字
            textPaint.setColor(0xFF888888);
            textPaint.setTextSize(dp(11));
            textPaint.setFakeBoldText(true);
            c.drawText(ch == 0 ? "L" : "R", left + dp(10), y + dp(13), textPaint);
            textPaint.setFakeBoldText(false);
            drawBar(c, g, ch, barLeft, y, barRight, y + barH);
            // PEAK ランプ
            float lampCx = barRight + dp(12);
            float lampCy = y + barH / 2;
            paint.setColor(lampMs[g][ch] > 0 ? 0xFFFF2828 : 0xFF351010);
            c.drawCircle(lampCx, lampCy, dp(5), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xFF565656);
            c.drawCircle(lampCx, lampCy, dp(5), paint);
            paint.setStyle(Paint.Style.FILL);
            // 数値 dB
            textPaint.setColor(0xFFE8E8E8);
            textPaint.setTextSize(dp(11));
            String val = String.format(java.util.Locale.US, "%.1f", dispDb[g][ch]);
            c.drawText(val, barRight + dp(22), y + dp(13), textPaint);
        }
        // 目盛り（グループ下端）: -40 / -20 / -6 / 0
        textPaint.setColor(0xFF777777);
        textPaint.setTextSize(dp(8));
        float scaleY = bottom - dp(5);
        String[] labels = {"-40", "-20", "-6", "0"};
        double[] vals = {-40, -20, -6, 0};
        for (int i = 0; i < labels.length; i++) {
            float x = (float) (barLeft + (vals[i] - MIN_DB) / (MAX_DB - MIN_DB) * (barRight - barLeft));
            float tw = textPaint.measureText(labels[i]);
            float dx = labels[i].equals("0") ? x - tw : x - tw / 2;
            c.drawText(labels[i], dx, scaleY, textPaint);
        }
    }

    private void drawBar(Canvas c, int g, int ch, float l, float t, float r, float b) {
        // バー地
        paint.setColor(0xFF080808);
        c.drawRect(l, t, r, b, paint);
        double db = dispDb[g][ch];
        float levelX = (float) (l + (db - MIN_DB) / (MAX_DB - MIN_DB) * (r - l));
        for (int[] band : BANDS) {
            float bandL = (float) (l + (band[0] - MIN_DB) / (MAX_DB - MIN_DB) * (r - l));
            float bandR = (float) (l + (band[1] - MIN_DB) / (MAX_DB - MIN_DB) * (r - l));
            // 消灯色で帯域全体を敷く
            paint.setColor(band[3]);
            c.drawRect(bandL, t, bandR, b, paint);
            if (segmentStyle) {
                float segW = (r - l) / 40f;
                for (int s = 0; s < 40; s++) {
                    double segDb = MIN_DB + s + 0.5;
                    if (segDb <= db) {
                        float sl = l + s * segW;
                        paint.setColor(bandColorFor(segDb, band));
                        if (paint.getColor() != 0) {
                            c.drawRect(sl + dp(0.5f), t + dp(1), sl + segW - dp(0.5f), b - dp(1), paint);
                        }
                    }
                }
            } else {
                float activeR = Math.min(levelX, bandR);
                if (activeR > bandL) {
                    paint.setColor(band[2]);
                    c.drawRect(bandL, t, activeR, b, paint);
                }
            }
        }
        // ピークホールド線（PC版: #fff2b0）
        float hx = (float) (l + (holdDb[g][ch] - MIN_DB) / (MAX_DB - MIN_DB) * (r - l));
        paint.setColor(0xFFFFF2B0);
        c.drawRect(hx - dp(1), t - dp(2), hx + dp(1), b + dp(2), paint);
        // バー枠
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFFA0A0A0);
        c.drawRect(l, t, r, b, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private int bandColorFor(double db, int[] band) {
        if (db >= band[0] && db < band[1]) return band[2];
        return 0;
    }
}
