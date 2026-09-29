package com.kooo.evcam.telemetry;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.List;
import java.util.Locale;

/**
 * 把一份 {@link VehicleState} 画成信息条的位图（宽 = 视频宽，高 100）。
 *
 * <h3>画法</h3>
 *
 * <ul>
 *   <li>只有图标、数字、动态条，没有文字说明（安全辅助那几项用行业通用的缩写当符号，和档位字母一样）。</li>
 *   <li>线条图标：灭的画细线轮廓（暗灰），亮的填实（极氪橙；双闪、开着的门、没系的安全带用红），
 *       没数据的更暗再盖一道斜杠。没数据不能画成灭 —— 转向灯亮着而我们读不到，画成「没打灯」就是在撒谎。</li>
 *   <li>底色是设计方案里夜间的「面」色（colors.xml 的 surface），上沿一条分隔线。</li>
 * </ul>
 *
 * <p>只在编码线程上用；快照版本没变就不重画（{@link #renderIfDue}）。</p>
 */
public final class InfoBarRenderer {

    public static final int HEIGHT = InfoBar.HEIGHT;

    // 颜色取自 values-night/colors.xml：surface / line / sunken / energy / recording / text_*
    private static final int BG = 0xFF2A2C30;
    private static final int DIVIDER = 0xFF34373B;
    private static final int TRACK = 0xFF3A3D42;
    private static final int ON = 0xFFF26B38;
    private static final int WARN = 0xFFF0555A;
    private static final int OFF = 0x99A3A6AB;
    private static final int UNKNOWN = 0x9975787D;
    private static final int TEXT = 0xFFF0F1F2;
    private static final int TEXT_DIM = 0xFFA3A6AB;

    /** 图标的线宽。 */
    private static final float LINE = 4f;

    private static final String[] ASSIST_LABELS = {"AEB", "FCW", "LDW", "LKA", "BSD", "RCW"};

    private final int width;
    private final List<InfoBarLayout.Placed> cells;
    private final Bitmap bitmap;
    private final Canvas canvas;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private long lastVersion = -1;
    private boolean drawnOnce;

    public InfoBarRenderer(int width, InfoBar.Options options) {
        this.width = Math.max(2, width);
        this.cells = InfoBarLayout.fit(this.width, options);
        this.bitmap = Bitmap.createBitmap(this.width, HEIGHT, Bitmap.Config.ARGB_8888);
        this.canvas = new Canvas(bitmap);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        mono.setTypeface(Typeface.MONOSPACE);
    }

    public int width() {
        return width;
    }

    public Bitmap bitmap() {
        return bitmap;
    }

    /** 放了几格（日志用）。 */
    public int cellCount() {
        return cells.size();
    }

    /**
     * 该重画就重画。
     *
     * @return true 表示位图变了，要重新上传
     */
    public boolean renderIfDue(VehicleState state) {
        if (drawnOnce && state.version == lastVersion) {
            return false;
        }
        draw(state);
        lastVersion = state.version;
        drawnOnce = true;
        return true;
    }

    public void recycle() {
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    // ================================================================= 整条

    private void draw(VehicleState s) {
        canvas.drawColor(BG);
        fill.setColor(DIVIDER);
        canvas.drawRect(0, 0, width, 2, fill);
        for (InfoBarLayout.Placed placed : cells) {
            canvas.save();
            canvas.translate(placed.x, 0);
            drawCell(placed.cell, s);
            canvas.restore();
        }
    }

    private void drawCell(InfoBarLayout.Cell cell, VehicleState s) {
        float cx = cell.width / 2f;
        float cy = HEIGHT / 2f;
        switch (cell) {
            case TURN_LEFT:
                drawTurn(cx, cy, true, s.turnSignal);
                break;
            case TURN_RIGHT:
                drawTurn(cx, cy, false, s.turnSignal);
                break;
            case HAZARD:
                drawHazard(cx, cy, s.hazard);
                break;
            case STEERING:
                drawSteering(cx, cy, s.steeringDegrees);
                break;
            case HANDS:
                drawHands(cx, cy, s.handsOnWheel);
                break;
            case GEAR:
                drawGear(cx, cy, s.gear);
                break;
            case PEDALS:
                drawPedals(cell.width, s.brake, s.throttle);
                break;
            case SPEED:
                drawSpeed(cell.width, s.speedKmh);
                break;
            case AUTO_HOLD:
                drawAutoHold(cx, cy, s.autoHold);
                break;
            case ACC:
                drawAcc(cx, cy, s.adaptiveCruise);
                break;
            case LCC:
                drawLcc(cx, cy, s.laneCentering);
                break;
            case STOCK_360:
                drawStock360(cx, cy, s.stockSurroundShown);
                break;
            case CABIN:
                drawCabin(cx, cy, s.doorsOpen, s.beltsUnbuckled);
                break;
            case DRL:
                drawLamp(cx, cy, 0, s.daytimeRunningLights);
                break;
            case LOW_BEAM:
                drawLamp(cx, cy, 1, s.lowBeam);
                break;
            case HIGH_BEAM:
                drawLamp(cx, cy, 2, s.highBeam);
                break;
            case FOG:
                drawLamp(cx, cy, 3, s.fogLights);
                break;
            case ASSIST:
                drawAssist(cell.width, new Boolean[]{s.aeb, s.forwardCollisionWarning,
                        s.laneDepartureWarning, s.laneKeepingAid, s.blindSpotAssist, s.rearCollisionWarning});
                break;
            case ODOMETER:
                drawOdometer(s.odometerKm);
                break;
            case POSITION:
                drawPosition(s.latitude, s.longitude);
                break;
            default:
                break;
        }
    }

    // ================================================================= 通用

    /** 亮 / 灭 / 没数据 三种颜色。 */
    private static int tone(Boolean on, boolean warn) {
        if (on == null) {
            return UNKNOWN;
        }
        if (!on) {
            return OFF;
        }
        return warn ? WARN : ON;
    }

    /** 没数据时盖一道斜杠。 */
    private void slash(float cx, float cy, float r) {
        stroke.setColor(UNKNOWN);
        stroke.setStrokeWidth(3f);
        canvas.drawLine(cx - r, cy + r, cx + r, cy - r, stroke);
    }

    private void centeredText(String s, float cx, float baseline, float size, int color, Paint paint) {
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(s, cx, baseline, paint);
    }

    /** 亮的填实，灭的和没数据的画轮廓。 */
    private void shape(Path p, Boolean on, boolean warn) {
        int color = tone(on, warn);
        if (Boolean.TRUE.equals(on)) {
            fill.setColor(color);
            canvas.drawPath(p, fill);
        } else {
            stroke.setColor(color);
            stroke.setStrokeWidth(LINE);
            canvas.drawPath(p, stroke);
        }
    }

    // ================================================================= 各格

    private void drawTurn(float cx, float cy, boolean left, Integer signal) {
        Boolean on = signal == null ? null
                : signal == (left ? VehicleState.TURN_LEFT : VehicleState.TURN_RIGHT);
        float d = left ? -1f : 1f;
        path.reset();
        path.moveTo(cx + d * 26, cy);
        path.lineTo(cx + d * 4, cy - 19);
        path.lineTo(cx + d * 4, cy - 9);
        path.lineTo(cx - d * 22, cy - 9);
        path.lineTo(cx - d * 22, cy + 9);
        path.lineTo(cx + d * 4, cy + 9);
        path.lineTo(cx + d * 4, cy + 19);
        path.close();
        shape(path, on, false);
        if (on == null) {
            slash(cx, cy, 24);
        }
    }

    private void drawHazard(float cx, float cy, Boolean on) {
        int color = tone(on, true);
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        triangle(cx, cy + 2, 27);
        canvas.drawPath(path, stroke);
        stroke.setStrokeWidth(LINE);
        triangle(cx, cy + 3, 14);
        canvas.drawPath(path, stroke);
        if (on == null) {
            slash(cx, cy, 27);
        }
    }

    private void triangle(float cx, float cy, float r) {
        path.reset();
        path.moveTo(cx, cy - r);
        path.lineTo(cx + r * 0.9f, cy + r * 0.7f);
        path.lineTo(cx - r * 0.9f, cy + r * 0.7f);
        path.close();
    }

    private void drawSteering(float cx, float cy, Float degrees) {
        int color = degrees == null ? UNKNOWN : ON;
        stroke.setColor(color);
        stroke.setStrokeWidth(5f);
        float r = 35f;
        canvas.save();
        if (degrees != null) {
            canvas.rotate(degrees, cx, cy);
        }
        canvas.drawCircle(cx, cy, r, stroke);
        // 三根辐条：左、右、下；中间留给角度数字
        float inner = 17f;
        canvas.drawLine(cx - r, cy, cx - inner, cy, stroke);
        canvas.drawLine(cx + r, cy, cx + inner, cy, stroke);
        canvas.drawLine(cx, cy + r, cx, cy + inner, stroke);
        canvas.restore();
        String label = degrees == null ? "--" : String.format(Locale.US, "%d°", Math.round(degrees));
        centeredText(label, cx, cy + 7, 17f, degrees == null ? TEXT_DIM : TEXT, text);
        if (degrees == null) {
            slash(cx, cy, r);
        }
    }

    /** 手扶方向盘：一个小方向盘，两侧各一只手（有手就填实）。 */
    private void drawHands(float cx, float cy, Boolean on) {
        int color = tone(on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(LINE);
        canvas.drawCircle(cx, cy, 22, stroke);
        canvas.drawLine(cx - 22, cy, cx - 9, cy, stroke);
        canvas.drawLine(cx + 22, cy, cx + 9, cy, stroke);
        canvas.drawLine(cx, cy + 22, cx, cy + 9, stroke);
        // 两只手：握在九点和三点的位置
        for (int side = -1; side <= 1; side += 2) {
            float hx = cx + side * 27;
            rect.set(hx - 7, cy - 12, hx + 7, cy + 12);
            if (Boolean.TRUE.equals(on)) {
                fill.setColor(color);
                canvas.drawRoundRect(rect, 6, 6, fill);
            } else {
                canvas.drawRoundRect(rect, 6, 6, stroke);
            }
        }
        if (on == null) {
            slash(cx, cy, 30);
        }
    }

    private void drawGear(float cx, float cy, String gear) {
        boolean known = gear != null && !gear.isEmpty();
        stroke.setColor(known ? ON : UNKNOWN);
        stroke.setStrokeWidth(5f);
        rect.set(cx - 23, cy - 23, cx + 23, cy + 23);
        canvas.drawRoundRect(rect, 8, 8, stroke);
        if (known) {
            centeredText(gear, cx, cy + 11, 32f, TEXT, text);
        } else {
            slash(cx, cy, 23);
        }
    }

    /** 刹车（上，红）和油门（下，橙）：横向的两根条，左边是深度数字。 */
    private void drawPedals(int cellWidth, Float brake, Float throttle) {
        drawDepthBar(cellWidth, 16f, brake, WARN);
        drawDepthBar(cellWidth, 62f, throttle, ON);
    }

    private void drawDepthBar(int cellWidth, float top, Float value, int color) {
        float bottom = top + 22f;
        String number = value == null ? "--" : String.format(Locale.US, "%d", Math.round(value * 100f));
        text.setTextSize(22f);
        text.setColor(value == null ? TEXT_DIM : TEXT);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(number, 44f, bottom - 3f, text);
        float left = 54f;
        float right = cellWidth - 8f;
        rect.set(left, top, right, bottom);
        fill.setColor(TRACK);
        canvas.drawRoundRect(rect, 6, 6, fill);
        if (value != null) {
            float w = (right - left) * Math.max(0f, Math.min(1f, value));
            if (w > 0f) {
                rect.set(left, top, left + Math.max(w, 6f), bottom);
                fill.setColor(color);
                canvas.drawRoundRect(rect, 6, 6, fill);
            }
        } else {
            slash(left + (right - left) / 2f, top + 11f, 11f);
        }
    }

    private void drawSpeed(int cellWidth, Float kmh) {
        String number = kmh == null ? "--" : String.format(Locale.US, "%d", Math.round(kmh));
        text.setTextSize(60f);
        text.setColor(kmh == null ? TEXT_DIM : TEXT);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(number, cellWidth - 58f, 71f, text);
        text.setTextSize(18f);
        text.setColor(TEXT_DIM);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("km/h", cellWidth - 50f, 71f, text);
    }

    private void drawAutoHold(float cx, float cy, Boolean on) {
        int color = tone(on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(5f);
        canvas.drawCircle(cx, cy, 25, stroke);
        centeredText("A", cx, cy + 10, 28f, on == null ? UNKNOWN : (on ? ON : OFF), text);
        if (on == null) {
            slash(cx, cy, 25);
        }
    }

    /** ACC：前面一辆车 + 两道雷达弧。 */
    private void drawAcc(float cx, float cy, Boolean on) {
        int color = tone(on, false);
        rect.set(cx - 34, cy - 11, cx - 8, cy + 11);
        path.reset();
        path.addRoundRect(rect, 6, 6, Path.Direction.CW);
        shape(path, on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(LINE);
        for (int i = 0; i < 2; i++) {
            float r = 14 + i * 13;
            rect.set(cx - 8 - r, cy - r, cx - 8 + r, cy + r);
            canvas.drawArc(rect, -40, 80, false, stroke);
        }
        if (on == null) {
            slash(cx, cy, 28);
        }
    }

    /** 车道居中：两条车道线之间一辆车。 */
    private void drawLcc(float cx, float cy, Boolean on) {
        int color = tone(on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(LINE);
        stroke.setPathEffect(new DashPathEffect(new float[]{9, 7}, 0));
        canvas.drawLine(cx - 28, cy + 27, cx - 17, cy - 27, stroke);
        canvas.drawLine(cx + 28, cy + 27, cx + 17, cy - 27, stroke);
        stroke.setPathEffect(null);
        rect.set(cx - 9, cy - 14, cx + 9, cy + 14);
        path.reset();
        path.addRoundRect(rect, 5, 5, Path.Direction.CW);
        shape(path, on, false);
        if (on == null) {
            slash(cx, cy, 28);
        }
    }

    /** 原厂 360 画面：俯视的车，四周四段弧（环视）；显示中就亮。 */
    private void drawStock360(float cx, float cy, Boolean shown) {
        int color = tone(shown, false);
        rect.set(cx - 8, cy - 14, cx + 8, cy + 14);
        path.reset();
        path.addRoundRect(rect, 4, 4, Path.Direction.CW);
        shape(path, shown, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(LINE);
        rect.set(cx - 27, cy - 27, cx + 27, cy + 27);
        for (int start = -160; start < 200; start += 90) {
            canvas.drawArc(rect, start, 50, false, stroke);
        }
        if (shown == null) {
            slash(cx, cy, 28);
        }
    }

    /**
     * 车厢：俯视的车身，四扇门开着的翘出来变红；车里五个座位（前两后三）的安全带，
     * 没系的填红，系着的画轮廓。
     */
    private void drawCabin(float cx, float cy, Integer doors, Integer belts) {
        boolean unknown = doors == null && belts == null;
        int body = unknown ? UNKNOWN : TEXT_DIM;
        stroke.setColor(body);
        stroke.setStrokeWidth(LINE);
        rect.set(cx - 28, cy - 38, cx + 28, cy + 38);
        canvas.drawRoundRect(rect, 14, 14, stroke);

        int[] doorBits = {VehicleState.FRONT_LEFT, VehicleState.FRONT_RIGHT,
                VehicleState.REAR_LEFT, VehicleState.REAR_RIGHT};
        for (int i = 0; i < 4; i++) {
            boolean left = i % 2 == 0;
            boolean front = i < 2;
            Boolean open = doors == null ? null : (doors & doorBits[i]) != 0;
            float hx = left ? cx - 28 : cx + 28;
            float hy = front ? cy - 28 : cy + 2;
            canvas.save();
            if (Boolean.TRUE.equals(open)) {
                canvas.rotate(left ? 40f : -40f, hx, hy);
            }
            rect.set(left ? hx - 7 : hx, hy, left ? hx : hx + 7, hy + 22);
            if (Boolean.TRUE.equals(open)) {
                fill.setColor(WARN);
                canvas.drawRoundRect(rect, 2, 2, fill);
            } else {
                stroke.setColor(open == null ? UNKNOWN : OFF);
                stroke.setStrokeWidth(3f);
                canvas.drawRoundRect(rect, 2, 2, stroke);
            }
            canvas.restore();
        }

        int[] beltBits = {VehicleState.FRONT_LEFT, VehicleState.FRONT_RIGHT,
                VehicleState.REAR_LEFT, VehicleState.REAR_CENTER, VehicleState.REAR_RIGHT};
        float[] sx = {cx - 12, cx + 12, cx - 16, cx, cx + 16};
        float[] sy = {cy - 17, cy - 17, cy + 16, cy + 16, cy + 16};
        for (int i = 0; i < 5; i++) {
            Boolean unbuckled = belts == null ? null : (belts & beltBits[i]) != 0;
            drawSeat(sx[i], sy[i], unbuckled);
        }
        if (unknown) {
            slash(cx, cy, 34);
        }
    }

    /** 一个座位：小方块加一条斜着的带子。 */
    private void drawSeat(float x, float y, Boolean unbuckled) {
        rect.set(x - 6.5f, y - 6.5f, x + 6.5f, y + 6.5f);
        if (Boolean.TRUE.equals(unbuckled)) {
            fill.setColor(WARN);
            canvas.drawRoundRect(rect, 2, 2, fill);
            stroke.setColor(BG);
        } else {
            stroke.setColor(unbuckled == null ? UNKNOWN : OFF);
            stroke.setStrokeWidth(2.5f);
            canvas.drawRoundRect(rect, 2, 2, stroke);
        }
        stroke.setStrokeWidth(2.5f);
        canvas.drawLine(x - 5, y - 5, x + 5, y + 5, stroke);
    }

    /**
     * 灯：右边一个灯罩，左边射出去的光线。四种靠光线的样子分：
     *
     * @param kind 0 日行灯（三道扇形散开）、1 近光（三道平行、斜向下）、2 远光（三道平行、水平）、
     *             3 雾灯（斜向下 + 一道竖着的波浪穿过）
     */
    private void drawLamp(float cx, float cy, int kind, Boolean on) {
        int color = tone(on, false);
        // 灯罩：D 形，平边朝左
        float edge = cx - 2;
        path.reset();
        path.moveTo(edge, cy - 22);
        rect.set(edge - 20, cy - 22, edge + 20, cy + 22);
        path.arcTo(rect, -90, 180, false);
        path.close();
        shape(path, on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(LINE);
        float x1 = edge - 8;
        float x0 = edge - 32;
        if (kind == 0) {
            canvas.drawLine(x1, cy - 4, x0 + 4, cy - 18, stroke);
            canvas.drawLine(x1, cy, x0, cy, stroke);
            canvas.drawLine(x1, cy + 4, x0 + 4, cy + 18, stroke);
        } else {
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * 14;
                float y0 = kind == 2 ? y : y + 7;
                canvas.drawLine(x1, y, x0, y0, stroke);
            }
        }
        if (kind == 3) {
            // 雾：一道竖着的波浪穿过光线
            path.reset();
            float wx = edge - 24;
            path.moveTo(wx, cy - 24);
            for (int i = 0; i < 4; i++) {
                path.quadTo(wx + (i % 2 == 0 ? 7 : -7), cy - 24 + i * 12 + 6, wx, cy - 24 + (i + 1) * 12);
            }
            stroke.setStrokeWidth(3f);
            canvas.drawPath(path, stroke);
        }
        if (on == null) {
            slash(cx, cy, 28);
        }
    }

    /** 六项安全辅助：两行三列的小标牌，开着的亮，关着的暗，读不到的加斜杠。 */
    private void drawAssist(int cellWidth, Boolean[] states) {
        float badgeW = 52f;
        float badgeH = 34f;
        float gap = 6f;
        float x0 = (cellWidth - (3 * badgeW + 2 * gap)) / 2f;
        for (int i = 0; i < ASSIST_LABELS.length; i++) {
            float left = x0 + (i % 3) * (badgeW + gap);
            float top = i < 3 ? 12f : 54f;
            Boolean on = states[i];
            int color = tone(on, false);
            rect.set(left, top, left + badgeW, top + badgeH);
            stroke.setColor(color);
            stroke.setStrokeWidth(3f);
            canvas.drawRoundRect(rect, 8, 8, stroke);
            centeredText(ASSIST_LABELS[i], left + badgeW / 2f, top + 23f, 15f,
                    on == null ? UNKNOWN : (on ? ON : OFF), text);
            if (on == null) {
                stroke.setColor(UNKNOWN);
                stroke.setStrokeWidth(2f);
                canvas.drawLine(left + 4, top + badgeH - 4, left + badgeW - 4, top + 4, stroke);
            }
        }
    }

    private void drawOdometer(Float km) {
        String label = km == null ? "-- km" : String.format(Locale.US, "%d km", Math.round(km));
        mono.setTextSize(26f);
        mono.setColor(km == null ? TEXT_DIM : TEXT);
        mono.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label, 4, 60, mono);
    }

    /** 经纬度：两行，纬度在上。 */
    private void drawPosition(Double lat, Double lon) {
        boolean known = lat != null && lon != null;
        mono.setTextSize(22f);
        mono.setColor(known ? TEXT : TEXT_DIM);
        mono.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(known ? String.format(Locale.US, "%.6f", lat) : "--", 4, 44, mono);
        canvas.drawText(known ? String.format(Locale.US, "%.6f", lon) : "--", 4, 76, mono);
    }
}
