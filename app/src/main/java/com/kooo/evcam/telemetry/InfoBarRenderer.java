package com.kooo.evcam.telemetry;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.List;
import java.util.Locale;

/**
 * 把一份 {@link VehicleState} 画成信息条的位图（宽 = 视频宽，高 100）。
 *
 * <h3>画法（v3，设计稿 2026-09-29）</h3>
 *
 * <ul>
 *   <li>录进视频要经过 H.265 压缩，所以：线宽不小于 6 px，最小形状不小于 8 px，能填实的填实；
 *       灭的用实心中灰，不用半透明；没数据的用深灰底加一道亮斜杠；数字加粗、不小于 26 px；
 *       不用虚线、点阵和 1–3 px 的缝。</li>
 *   <li>亮是极氪橙；双闪、开着的门、没系的安全带用红；刹车条红、油门条绿（明度也分得开）；
 *       方向盘角度左偏黄、右偏白，不带正负号。</li>
 *   <li>日行灯那一格以 7X 正脸为底：车身轮廓、星门灯带、徽标、灯带下沿两条白色日行灯线，
 *       亮时带光晕 —— 整条里唯一带光晕、唯一有「画」的一格。</li>
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
    /** 油门条：绿，比红亮，色盲和压缩糊了都靠明度分得开。 */
    private static final int GO = 0xFF5BD37A;
    /** 灭：实心中灰，不用半透明。 */
    private static final int OFF = 0xFF7C8087;
    /** 没数据：深灰底、浅灰边、亮斜杠。 */
    private static final int UNKNOWN_FILL = 0xFF3D4046;
    private static final int UNKNOWN_LINE = 0xFF8A8D93;
    private static final int SLASH = 0xFFF0F1F2;
    private static final int TEXT = 0xFFF0F1F2;
    private static final int TEXT_DIM = 0xFFA3A6AB;
    /** 方向盘左偏的数字。 */
    private static final int LEFT_YELLOW = 0xFFFFD54F;
    /** 车身轮廓（日行灯、车厢）。 */
    private static final int OUTLINE = 0xFFA3A6AB;
    /** 日行灯的核心色（偏白）和亮着的星门灯带。 */
    private static final int LAMP_CORE = 0xFFFFF3EA;
    private static final int BAND_LIT = 0xFFD0602E;

    private static final String[] ASSIST_LABELS = {"AEB", "FCW", "LDW", "LKA", "BSD", "RCW"};

    private final int width;
    private final List<InfoBarLayout.Placed> cells;
    private final Bitmap bitmap;
    private final Canvas canvas;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        mono.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
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
                drawDaytimeLights(s.daytimeRunningLights);
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
                drawAssist(new Boolean[]{s.aeb, s.forwardCollisionWarning,
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

    /** 线条的颜色：亮 / 灭 / 没数据。 */
    private static int lineTone(Boolean on, boolean warn) {
        if (on == null) {
            return UNKNOWN_LINE;
        }
        if (!on) {
            return OFF;
        }
        return warn ? WARN : ON;
    }

    /** 实心形状：亮填橙（或红），灭填中灰，没数据深灰底加浅灰边。 */
    private void solid(Path p, Boolean on, boolean warn) {
        if (on == null) {
            fill.setColor(UNKNOWN_FILL);
            canvas.drawPath(p, fill);
            stroke.setColor(UNKNOWN_LINE);
            stroke.setStrokeWidth(4f);
            canvas.drawPath(p, stroke);
        } else {
            fill.setColor(on ? (warn ? WARN : ON) : OFF);
            canvas.drawPath(p, fill);
        }
    }

    private void solidRect(float l, float t, float r, float b, float radius, Boolean on, boolean warn) {
        rect.set(l, t, r, b);
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        solid(path, on, warn);
    }

    /** 没数据时盖一道亮斜杠（左下到右上）。 */
    private void slash(float x0, float y0, float x1, float y1) {
        stroke.setColor(SLASH);
        stroke.setStrokeWidth(6f);
        canvas.drawLine(x0, y0, x1, y1, stroke);
    }

    private void line(float x0, float y0, float x1, float y1, float w, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(w);
        canvas.drawLine(x0, y0, x1, y1, stroke);
    }

    private void centeredText(String s, float cx, float baseline, float size, int color, Paint paint) {
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(s, cx, baseline, paint);
    }

    // ================================================================= 各格

    private void drawTurn(float cx, float cy, boolean left, Integer signal) {
        Boolean on = signal == null ? null
                : signal == (left ? VehicleState.TURN_LEFT : VehicleState.TURN_RIGHT);
        float d = left ? -1f : 1f;
        path.reset();
        path.moveTo(cx + d * 27, cy);
        path.lineTo(cx + d * 3, cy - 21);
        path.lineTo(cx + d * 3, cy - 10);
        path.lineTo(cx - d * 23, cy - 10);
        path.lineTo(cx - d * 23, cy + 10);
        path.lineTo(cx + d * 3, cy + 10);
        path.lineTo(cx + d * 3, cy + 21);
        path.close();
        solid(path, on, false);
        if (on == null) {
            slash(cx - 26, cy + 26, cx + 26, cy - 26);
        }
    }

    private void drawHazard(float cx, float cy, Boolean on) {
        int color = lineTone(on, true);
        stroke.setColor(color);
        stroke.setStrokeWidth(8f);
        triangle(cx, cy - 27, cy + 20);
        canvas.drawPath(path, stroke);
        stroke.setStrokeWidth(6f);
        triangle(cx, cy - 11, cy + 12);
        canvas.drawPath(path, stroke);
        if (on == null) {
            slash(cx - 28, cy + 28, cx + 28, cy - 28);
        }
    }

    private void triangle(float cx, float top, float bottom) {
        float half = (bottom - top) * 0.53f;
        path.reset();
        path.moveTo(cx, top);
        path.lineTo(cx + half, bottom);
        path.lineTo(cx - half, bottom);
        path.close();
    }

    /** 方向盘：整只随转角转；数字居中、度数符号挂在右边；左偏黄、右偏白，不带正负号。 */
    private void drawSteering(float cx, float cy, Float degrees) {
        int ring = degrees == null ? UNKNOWN_LINE : ON;
        float r = 34f;
        canvas.save();
        if (degrees != null) {
            canvas.rotate(degrees, cx, cy);
        }
        stroke.setColor(ring);
        stroke.setStrokeWidth(7f);
        canvas.drawCircle(cx, cy, r, stroke);
        float inner = 22f;
        canvas.drawLine(cx - r, cy, cx - inner, cy, stroke);
        canvas.drawLine(cx + r, cy, cx + inner, cy, stroke);
        canvas.drawLine(cx, cy + r, cx, cy + inner, stroke);
        canvas.restore();
        if (degrees == null) {
            centeredText("--", cx, cy + 11, 32f, TEXT_DIM, text);
            slash(cx - 34, cy + 34, cx + 34, cy - 34);
            return;
        }
        int color = degrees < 0 ? LEFT_YELLOW : TEXT;
        String digits = String.format(Locale.US, "%d", Math.abs(Math.round(degrees)));
        text.setTextSize(32f);
        float half = text.measureText(digits) / 2f;
        centeredText(digits, cx, cy + 11, 32f, color, text);
        text.setTextSize(20f);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("°", cx + half + 2, cy, text);
    }

    /** 手扶方向盘：小方向盘，两侧各一只手。 */
    private void drawHands(float cx, float cy, Boolean on) {
        int color = lineTone(on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        canvas.drawCircle(cx, cy, 22, stroke);
        canvas.drawLine(cx - 22, cy, cx - 9, cy, stroke);
        canvas.drawLine(cx + 22, cy, cx + 9, cy, stroke);
        canvas.drawLine(cx, cy + 22, cx, cy + 9, stroke);
        for (int side = -1; side <= 1; side += 2) {
            float hx = cx + side * 27;
            solidRect(hx - 8, cy - 13, hx + 8, cy + 13, 7, on, false);
        }
        if (on == null) {
            slash(cx - 30, cy + 30, cx + 30, cy - 30);
        }
    }

    private void drawGear(float cx, float cy, String gear) {
        boolean known = gear != null && !gear.isEmpty();
        stroke.setColor(known ? ON : UNKNOWN_LINE);
        stroke.setStrokeWidth(6f);
        rect.set(cx - 24, cy - 24, cx + 24, cy + 24);
        canvas.drawRoundRect(rect, 9, 9, stroke);
        if (known) {
            centeredText(gear, cx, cy + 13, 36f, TEXT, text);
        } else {
            slash(cx - 24, cy + 24, cx + 24, cy - 24);
        }
    }

    /** 刹车（上，红）和油门（下，绿）：横向的两根条，左边是深度数字。 */
    private void drawPedals(int cellWidth, Float brake, Float throttle) {
        drawDepthBar(cellWidth, 14f, brake, WARN);
        drawDepthBar(cellWidth, 62f, throttle, GO);
    }

    private void drawDepthBar(int cellWidth, float top, Float value, int color) {
        float bottom = top + 24f;
        String number = value == null ? "--" : String.format(Locale.US, "%d", Math.round(value * 100f));
        text.setTextSize(30f);
        text.setColor(value == null ? TEXT_DIM : TEXT);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(number, 52f, bottom - 1f, text);
        float left = 62f;
        float right = cellWidth - 8f;
        rect.set(left, top, right, bottom);
        fill.setColor(TRACK);
        canvas.drawRoundRect(rect, 6, 6, fill);
        if (value != null) {
            float w = (right - left) * Math.max(0f, Math.min(1f, value));
            if (w > 0f) {
                rect.set(left, top, left + Math.max(w, 8f), bottom);
                fill.setColor(color);
                canvas.drawRoundRect(rect, 6, 6, fill);
            }
        } else {
            float mid = (left + right) / 2f;
            slash(mid - 12, bottom - 2, mid + 12, top + 2);
        }
    }

    private void drawSpeed(int cellWidth, Float kmh) {
        String number = kmh == null ? "--" : String.format(Locale.US, "%d", Math.round(kmh));
        text.setTextSize(64f);
        text.setColor(kmh == null ? TEXT_DIM : TEXT);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(number, cellWidth - 60f, 72f, text);
        text.setTextSize(22f);
        text.setColor(TEXT_DIM);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("km/h", cellWidth - 52f, 72f, text);
    }

    private void drawAutoHold(float cx, float cy, Boolean on) {
        int color = lineTone(on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        canvas.drawCircle(cx, cy, 25, stroke);
        centeredText("A", cx, cy + 12, 32f, color, text);
        if (on == null) {
            slash(cx - 25, cy + 25, cx + 25, cy - 25);
        }
    }

    /** ACC：前面一辆车 + 两道雷达弧。 */
    private void drawAcc(float cx, float cy, Boolean on) {
        int color = lineTone(on, false);
        solidRect(cx - 34, cy - 12, cx - 6, cy + 12, 6, on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        for (int i = 0; i < 2; i++) {
            float r = 14 + i * 13;
            rect.set(cx - 10 - r, cy - r, cx - 10 + r, cy + r);
            canvas.drawArc(rect, -40, 80, false, stroke);
        }
        if (on == null) {
            slash(cx - 28, cy + 28, cx + 28, cy - 28);
        }
    }

    /** 车道居中：两条实线车道线之间一辆车。 */
    private void drawLcc(float cx, float cy, Boolean on) {
        int color = lineTone(on, false);
        line(cx - 29, cy + 28, cx - 17, cy - 28, 7f, color);
        line(cx + 29, cy + 28, cx + 17, cy - 28, 7f, color);
        solidRect(cx - 10, cy - 16, cx + 10, cy + 16, 6, on, false);
        if (on == null) {
            slash(cx - 28, cy + 28, cx + 28, cy - 28);
        }
    }

    /** 原厂 360 画面：俯视的车，四周四段弧（环视）；显示中就亮。 */
    private void drawStock360(float cx, float cy, Boolean shown) {
        int color = lineTone(shown, false);
        solidRect(cx - 9, cy - 15, cx + 9, cy + 15, 5, shown, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        rect.set(cx - 27, cy - 27, cx + 27, cy + 27);
        for (int start = -160; start < 200; start += 90) {
            canvas.drawArc(rect, start, 50, false, stroke);
        }
        if (shown == null) {
            slash(cx - 28, cy + 28, cx + 28, cy - 28);
        }
    }

    /**
     * 车厢：俯视的车身，四扇门开着的翘出来变红；车里五个座位（前两后三）的安全带，
     * 没系的填红，系着的画轮廓。
     */
    private void drawCabin(float cx, float cy, Integer doors, Integer belts) {
        boolean unknown = doors == null && belts == null;
        int body = unknown ? UNKNOWN_LINE : OUTLINE;
        stroke.setColor(body);
        stroke.setStrokeWidth(6f);
        rect.set(cx - 32, cy - 40, cx + 32, cy + 40);
        canvas.drawRoundRect(rect, 16, 16, stroke);

        int[] doorBits = {VehicleState.FRONT_LEFT, VehicleState.FRONT_RIGHT,
                VehicleState.REAR_LEFT, VehicleState.REAR_RIGHT};
        for (int i = 0; i < 4; i++) {
            boolean left = i % 2 == 0;
            boolean front = i < 2;
            Boolean open = doors == null ? null : (doors & doorBits[i]) != 0;
            float hx = left ? cx - 32 : cx + 32;
            float hy = front ? cy - 28 : cy + 4;
            canvas.save();
            if (Boolean.TRUE.equals(open)) {
                canvas.rotate(left ? 40f : -40f, hx, hy);
            }
            solidRect(left ? hx - 9 : hx, hy, left ? hx : hx + 9, hy + 24, 3, open, true);
            canvas.restore();
        }

        int[] beltBits = {VehicleState.FRONT_LEFT, VehicleState.FRONT_RIGHT,
                VehicleState.REAR_LEFT, VehicleState.REAR_CENTER, VehicleState.REAR_RIGHT};
        float[] sx = {cx - 14, cx + 14, cx - 18, cx, cx + 18};
        float[] sy = {cy - 18, cy - 18, cy + 18, cy + 18, cy + 18};
        for (int i = 0; i < 5; i++) {
            Boolean unbuckled = belts == null ? null : (belts & beltBits[i]) != 0;
            drawSeat(sx[i], sy[i], unbuckled);
        }
        if (unknown) {
            slash(cx - 34, cy + 34, cx + 34, cy - 34);
        }
    }

    /** 一个座位：小方块加一条斜着的带子。 */
    private void drawSeat(float x, float y, Boolean unbuckled) {
        rect.set(x - 8, y - 8, x + 8, y + 8);
        if (Boolean.TRUE.equals(unbuckled)) {
            fill.setColor(WARN);
            canvas.drawRoundRect(rect, 3, 3, fill);
            line(x - 5, y - 5, x + 5, y + 5, 4f, BG);
        } else {
            int color = unbuckled == null ? UNKNOWN_LINE : OUTLINE;
            stroke.setColor(color);
            stroke.setStrokeWidth(4f);
            canvas.drawRoundRect(rect, 3, 3, stroke);
            line(x - 5, y - 5, x + 5, y + 5, 4f, color);
        }
    }

    /**
     * 日行灯：以 7X 正脸为底 —— 车身轮廓（车顶、A 柱、肩线、轮子）、星门灯带、正中的徽标、
     * 车牌，以及灯带下沿左右两条白色日行灯线（向外端上挑收进转角灯组）。
     * 亮时两条线带橙色光晕、灯带亮起并向四周散光；灭时整组灰、轮廓还在。格子 130 宽。
     */
    private void drawDaytimeLights(Boolean on) {
        boolean lit = Boolean.TRUE.equals(on);
        int lineColor = on == null ? UNKNOWN_LINE : OUTLINE;
        // 车身轮廓
        path.reset();
        path.moveTo(20, 84);
        path.lineTo(22, 50);
        path.quadTo(24, 44, 30, 42);
        path.lineTo(44, 20);
        path.quadTo(46, 18, 50, 18);
        path.lineTo(80, 18);
        path.quadTo(84, 18, 86, 20);
        path.lineTo(100, 42);
        path.quadTo(106, 44, 108, 50);
        path.lineTo(110, 84);
        path.close();
        stroke.setColor(lineColor);
        stroke.setStrokeWidth(5f);
        canvas.drawPath(path, stroke);
        // 风挡
        path.reset();
        path.moveTo(36, 42);
        path.lineTo(48, 24);
        path.lineTo(82, 24);
        path.lineTo(94, 42);
        path.close();
        stroke.setColor(on == null ? UNKNOWN_LINE : OFF);
        stroke.setStrokeWidth(4f);
        canvas.drawPath(path, stroke);
        // 轮子、车牌
        fill.setColor(UNKNOWN_FILL);
        rect.set(16, 80, 34, 90);
        canvas.drawRoundRect(rect, 3, 3, fill);
        rect.set(96, 80, 114, 90);
        canvas.drawRoundRect(rect, 3, 3, fill);
        rect.set(52, 70, 78, 80);
        canvas.drawRoundRect(rect, 2, 2, fill);
        // 星门灯带：亮时先散一圈橙光，带子本身也亮
        if (lit) {
            glow.setStyle(Paint.Style.FILL);
            glow.setColor(ON);
            glow.setAlpha(140);
            glow.setMaskFilter(new BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL));
            rect.set(22, 50, 108, 64);
            canvas.drawRoundRect(rect, 6, 6, glow);
        }
        fill.setColor(lit ? BAND_LIT : UNKNOWN_FILL);
        rect.set(24, 52, 106, 62);
        canvas.drawRoundRect(rect, 4, 4, fill);
        // 两条日行灯线
        path.reset();
        path.moveTo(58, 66);
        path.lineTo(32, 66);
        path.quadTo(26, 66, 24, 62);
        path.moveTo(72, 66);
        path.lineTo(98, 66);
        path.quadTo(104, 66, 106, 62);
        if (lit) {
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(11f);
            glow.setColor(ON);
            glow.setAlpha(240);
            glow.setMaskFilter(new BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL));
            canvas.drawPath(path, glow);
            glow.setMaskFilter(null);
        }
        stroke.setColor(lit ? LAMP_CORE : (on == null ? UNKNOWN_LINE : OFF));
        stroke.setStrokeWidth(5f);
        canvas.drawPath(path, stroke);
        // 徽标
        fill.setColor(lit ? LAMP_CORE : (on == null ? UNKNOWN_LINE : OFF));
        rect.set(61, 54, 69, 60);
        canvas.drawRoundRect(rect, 1.5f, 1.5f, fill);
        if (on == null) {
            slash(20, 84, 110, 16);
        }
    }

    /**
     * 灯：右边一个灯罩（亮填实、灭画轮廓），左边射出去的光线，靠光线的样子分：
     *
     * @param kind 1 近光（三道平行、斜向下）、2 远光（三道平行、水平）、3 雾灯（斜向下 + 一道竖着的波浪）
     */
    private void drawLamp(float cx, float cy, int kind, Boolean on) {
        int color = lineTone(on, false);
        path.reset();
        path.moveTo(cx, cy - 23);
        rect.set(cx - 20, cy - 23, cx + 20, cy + 23);
        path.arcTo(rect, -90, 180, false);
        path.close();
        if (Boolean.TRUE.equals(on)) {
            fill.setColor(ON);
            canvas.drawPath(path, fill);
        } else if (on == null) {
            fill.setColor(UNKNOWN_FILL);
            canvas.drawPath(path, fill);
            stroke.setColor(UNKNOWN_LINE);
            stroke.setStrokeWidth(4f);
            canvas.drawPath(path, stroke);
        } else {
            stroke.setColor(OFF);
            stroke.setStrokeWidth(6f);
            canvas.drawPath(path, stroke);
        }
        float x1 = cx - 10;
        float x0 = cx - 34;
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * 14;
            line(x1, y, x0, kind == 2 ? y : y + 8, 6f, color);
        }
        if (kind == 3) {
            path.reset();
            float wx = cx - 24;
            path.moveTo(wx, cy - 26);
            for (int i = 0; i < 4; i++) {
                path.quadTo(wx + (i % 2 == 0 ? 8 : -8), cy - 26 + i * 14 + 7, wx, cy - 26 + (i + 1) * 14);
            }
            stroke.setColor(color);
            stroke.setStrokeWidth(5f);
            canvas.drawPath(path, stroke);
        }
        if (on == null) {
            slash(cx - 28, cy + 28, cx + 28, cy - 28);
        }
    }

    /** 六项安全辅助：两行三列的标牌，开着的亮，关着的暗，读不到的深灰底加斜杠。 */
    private void drawAssist(Boolean[] states) {
        float badgeW = 58f;
        float badgeH = 36f;
        float gap = 6f;
        float x0 = 7f;
        for (int i = 0; i < ASSIST_LABELS.length; i++) {
            float left = x0 + (i % 3) * (badgeW + gap);
            float top = i < 3 ? 10f : 54f;
            Boolean on = states[i];
            rect.set(left, top, left + badgeW, top + badgeH);
            if (on == null) {
                fill.setColor(UNKNOWN_FILL);
                canvas.drawRoundRect(rect, 9, 9, fill);
                stroke.setColor(UNKNOWN_LINE);
                stroke.setStrokeWidth(4f);
            } else {
                stroke.setColor(on ? ON : OFF);
                stroke.setStrokeWidth(5f);
            }
            canvas.drawRoundRect(rect, 9, 9, stroke);
            centeredText(ASSIST_LABELS[i], left + badgeW / 2f, top + 25f, 20f,
                    on == null ? UNKNOWN_LINE : (on ? ON : OFF), text);
            if (on == null) {
                line(left + 5, top + badgeH - 5, left + badgeW - 5, top + 5, 5f, SLASH);
            }
        }
    }

    private void drawOdometer(Float km) {
        String label = km == null ? "-- km" : String.format(Locale.US, "%d km", Math.round(km));
        mono.setTextSize(28f);
        mono.setColor(km == null ? TEXT_DIM : TEXT);
        mono.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label, 4, 61, mono);
    }

    /** 经纬度：两行，纬度在上。 */
    private void drawPosition(Double lat, Double lon) {
        boolean known = lat != null && lon != null;
        mono.setTextSize(26f);
        mono.setColor(known ? TEXT : TEXT_DIM);
        mono.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(known ? String.format(Locale.US, "%.6f", lat) : "--", 4, 44, mono);
        canvas.drawText(known ? String.format(Locale.US, "%.6f", lon) : "--", 4, 78, mono);
    }
}
