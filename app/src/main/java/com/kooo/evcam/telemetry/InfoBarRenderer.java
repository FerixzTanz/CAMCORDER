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
 *   <li>只有图标、数字、动态条，没有文字说明。</li>
 *   <li>三种样子分得开：<b>亮</b>（极氪橙；双闪、开着的门、没系的安全带用红）、
 *       <b>灭</b>（暗灰）、<b>没数据</b>（更暗，加一道斜杠）。没数据不能画成灭 ——
 *       转向灯亮着而我们读不到，画成「没打灯」就是在撒谎。</li>
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
    private static final int OFF = 0x80A3A6AB;
    private static final int UNKNOWN = 0x9975787D;
    private static final int TEXT = 0xFFF0F1F2;
    private static final int TEXT_DIM = 0xFFA3A6AB;

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
            case GEAR:
                drawGear(cx, cy, s.gear);
                break;
            case THROTTLE:
                drawPedal(cell.width, s.throttle, false);
                break;
            case BRAKE:
                drawPedal(cell.width, s.brake, true);
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
            case DOORS:
                drawDoors(cx, cy, s.doorsOpen);
                break;
            case BELTS:
                drawBelt(cx, cy, s.beltsUnbuckled);
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

    // ================================================================= 各格

    private void drawTurn(float cx, float cy, boolean left, Integer signal) {
        Boolean on = signal == null ? null
                : signal == (left ? VehicleState.TURN_LEFT : VehicleState.TURN_RIGHT);
        fill.setColor(tone(on, false));
        float d = left ? -1f : 1f;
        path.reset();
        // 箭头：头 + 尾
        path.moveTo(cx + d * 24, cy);
        path.lineTo(cx + d * 2, cy - 18);
        path.lineTo(cx + d * 2, cy - 8);
        path.lineTo(cx - d * 22, cy - 8);
        path.lineTo(cx - d * 22, cy + 8);
        path.lineTo(cx + d * 2, cy + 8);
        path.lineTo(cx + d * 2, cy + 18);
        path.close();
        canvas.drawPath(path, fill);
        if (on == null) {
            slash(cx, cy, 22);
        }
    }

    private void drawHazard(float cx, float cy, Boolean on) {
        int color = tone(on, true);
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        triangle(cx, cy + 2, 26);
        canvas.drawPath(path, stroke);
        stroke.setStrokeWidth(4f);
        triangle(cx, cy + 2, 14);
        canvas.drawPath(path, stroke);
        if (on == null) {
            slash(cx, cy, 26);
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
        stroke.setStrokeWidth(6f);
        float r = 36f;
        canvas.save();
        if (degrees != null) {
            canvas.rotate(degrees, cx, cy);
        }
        canvas.drawCircle(cx, cy, r, stroke);
        // 三根辐条：左、右、下；中间留给角度数字
        stroke.setStrokeWidth(5f);
        float inner = 17f;
        canvas.drawLine(cx - r, cy, cx - inner, cy, stroke);
        canvas.drawLine(cx + r, cy, cx + inner, cy, stroke);
        canvas.drawLine(cx, cy + r, cx, cy + inner, stroke);
        canvas.restore();
        String label = degrees == null ? "--" : String.format(Locale.US, "%d°", Math.round(degrees));
        centeredText(label, cx, cy + 7, degrees == null ? 18f : 17f, degrees == null ? TEXT_DIM : TEXT, text);
        if (degrees == null) {
            slash(cx, cy, r);
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

    /** 踏板：左边一个踏板图标（踩着就亮），右边一根竖条按开度填。 */
    private void drawPedal(int cellWidth, Float value, boolean brake) {
        Boolean active = value == null ? null : value > 0.02f;
        int color = tone(active, brake);
        // 踏板：斜放的圆角矩形
        float px = 30f;
        float py = HEIGHT / 2f;
        canvas.save();
        canvas.rotate(-14f, px, py);
        rect.set(px - 12, py - 22, px + 12, py + 22);
        fill.setColor(color);
        canvas.drawRoundRect(rect, 6, 6, fill);
        stroke.setColor(BG);
        stroke.setStrokeWidth(2.5f);
        for (int i = -1; i <= 1; i++) {
            canvas.drawLine(px - 7, py + i * 10, px + 7, py + i * 10, stroke);
        }
        canvas.restore();
        if (active == null) {
            slash(px, py, 20);
        }
        // 开度条
        float bx = cellWidth - 34f;
        float top = 16f;
        float bottom = HEIGHT - 16f;
        rect.set(bx, top, bx + 16, bottom);
        fill.setColor(TRACK);
        canvas.drawRoundRect(rect, 5, 5, fill);
        if (value != null) {
            float h = (bottom - top) * Math.max(0f, Math.min(1f, value));
            rect.set(bx, bottom - h, bx + 16, bottom);
            fill.setColor(brake ? WARN : ON);
            canvas.drawRoundRect(rect, 5, 5, fill);
        }
    }

    private void drawSpeed(int cellWidth, Float kmh) {
        String number = kmh == null ? "--" : String.format(Locale.US, "%d", Math.round(kmh));
        text.setTextSize(64f);
        text.setColor(kmh == null ? TEXT_DIM : TEXT);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(number, cellWidth - 74f, 72f, text);
        text.setTextSize(18f);
        text.setColor(TEXT_DIM);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("km/h", cellWidth - 64f, 72f, text);
    }

    private void drawAutoHold(float cx, float cy, Boolean on) {
        int color = tone(on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(5f);
        canvas.drawCircle(cx, cy, 25, stroke);
        centeredText("A", cx, cy + 10, 28f, on == null ? UNKNOWN : (on ? TEXT : OFF), text);
        if (on == null) {
            slash(cx, cy, 25);
        }
    }

    /** ACC：前面一辆车 + 两道雷达弧。 */
    private void drawAcc(float cx, float cy, Boolean on) {
        int color = tone(on, false);
        fill.setColor(color);
        rect.set(cx - 38, cy - 12, cx - 8, cy + 12);
        canvas.drawRoundRect(rect, 6, 6, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(5f);
        for (int i = 0; i < 2; i++) {
            float r = 16 + i * 14;
            rect.set(cx - 8 - r, cy - r, cx - 8 + r, cy + r);
            canvas.drawArc(rect, -40, 80, false, stroke);
        }
        if (on == null) {
            slash(cx, cy, 30);
        }
    }

    /** 车道居中：两条车道线之间一辆车。 */
    private void drawLcc(float cx, float cy, Boolean on) {
        int color = tone(on, false);
        stroke.setColor(color);
        stroke.setStrokeWidth(5f);
        stroke.setPathEffect(new DashPathEffect(new float[]{9, 7}, 0));
        canvas.drawLine(cx - 30, cy + 28, cx - 18, cy - 28, stroke);
        canvas.drawLine(cx + 30, cy + 28, cx + 18, cy - 28, stroke);
        stroke.setPathEffect(null);
        fill.setColor(color);
        rect.set(cx - 9, cy - 14, cx + 9, cy + 14);
        canvas.drawRoundRect(rect, 5, 5, fill);
        if (on == null) {
            slash(cx, cy, 30);
        }
    }

    /** 原厂 360 画面：俯视的车，四周四段弧（环视）；显示中就亮。 */
    private void drawStock360(float cx, float cy, Boolean shown) {
        int color = tone(shown, false);
        fill.setColor(color);
        rect.set(cx - 8, cy - 14, cx + 8, cy + 14);
        canvas.drawRoundRect(rect, 4, 4, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(4f);
        rect.set(cx - 27, cy - 27, cx + 27, cy + 27);
        for (int start = -160; start < 200; start += 90) {
            canvas.drawArc(rect, start, 50, false, stroke);
        }
        if (shown == null) {
            slash(cx, cy, 28);
        }
    }

    /** 车门：俯视的车身，四扇门开着的翘出来、变红。 */
    private void drawDoors(float cx, float cy, Integer mask) {
        boolean unknown = mask == null;
        int body = unknown ? UNKNOWN : TEXT_DIM;
        stroke.setColor(body);
        stroke.setStrokeWidth(4f);
        rect.set(cx - 20, cy - 36, cx + 20, cy + 36);
        canvas.drawRoundRect(rect, 12, 12, stroke);
        int[] bits = {VehicleState.FRONT_LEFT, VehicleState.FRONT_RIGHT,
                VehicleState.REAR_LEFT, VehicleState.REAR_RIGHT};
        for (int i = 0; i < 4; i++) {
            boolean left = i % 2 == 0;
            boolean front = i < 2;
            boolean open = !unknown && (mask & bits[i]) != 0;
            float hx = left ? cx - 20 : cx + 20;
            float hy = front ? cy - 26 : cy + 4;
            canvas.save();
            if (open) {
                canvas.rotate(left ? 40f : -40f, hx, hy);
            }
            fill.setColor(open ? WARN : body);
            rect.set(left ? hx - 6 : hx, hy, left ? hx : hx + 6, hy + 22);
            canvas.drawRoundRect(rect, 2, 2, fill);
            canvas.restore();
        }
        if (unknown) {
            slash(cx, cy, 30);
        }
    }

    /** 安全带：一条斜着的带子和扣，谁没系就红。 */
    private void drawBelt(float cx, float cy, Integer mask) {
        Boolean unbuckled = mask == null ? null : mask != 0;
        int color = tone(unbuckled, true);
        // 头
        fill.setColor(color);
        canvas.drawCircle(cx, cy - 22, 9, fill);
        // 肩和带子
        stroke.setColor(color);
        stroke.setStrokeWidth(7f);
        canvas.drawLine(cx - 16, cy - 6, cx + 16, cy - 6, stroke);
        canvas.drawLine(cx - 14, cy - 6, cx + 12, cy + 24, stroke);
        // 扣
        rect.set(cx + 6, cy + 14, cx + 20, cy + 26);
        canvas.drawRoundRect(rect, 3, 3, fill);
        if (unbuckled == null) {
            slash(cx, cy, 26);
        }
    }

    /**
     * 灯：右边一个灯罩，左边射出去的光线。
     *
     * @param kind 0 日行灯（短线）、1 近光（斜向下）、2 远光（平）、3 雾灯（斜线 + 一道波浪）
     */
    private void drawLamp(float cx, float cy, int kind, Boolean on) {
        int color = tone(on, false);
        fill.setColor(color);
        // 灯罩：D 形，平边朝左
        path.reset();
        path.moveTo(cx + 4, cy - 20);
        rect.set(cx - 14, cy - 20, cx + 22, cy + 20);
        path.arcTo(rect, -90, 180, false);
        path.lineTo(cx + 4, cy + 20);
        path.close();
        canvas.drawPath(path, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(4f);
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * 13;
            float x1 = cx - 4;
            float x0 = kind == 0 ? cx - 16 : cx - 28;
            float y0 = kind == 1 || kind == 3 ? y + 6 : y;
            canvas.drawLine(x0, y0, x1, y, stroke);
        }
        if (kind == 3) {
            // 雾：一道竖着的波浪穿过光线
            path.reset();
            float wx = cx - 22;
            path.moveTo(wx, cy - 22);
            for (int i = 0; i < 4; i++) {
                path.quadTo(wx + (i % 2 == 0 ? 6 : -6), cy - 22 + i * 11 + 5, wx, cy - 22 + (i + 1) * 11);
            }
            stroke.setStrokeWidth(3f);
            canvas.drawPath(path, stroke);
        }
        if (on == null) {
            slash(cx, cy, 26);
        }
    }

    private void drawOdometer(Float km) {
        String label = km == null ? "-- km" : String.format(Locale.US, "%d km", Math.round(km));
        mono.setTextSize(28f);
        mono.setColor(km == null ? TEXT_DIM : TEXT);
        mono.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label, 4, 62, mono);
    }

    private void drawPosition(Double lat, Double lon) {
        String label = lat == null || lon == null ? "--"
                : String.format(Locale.US, "%.6f, %.6f", lat, lon);
        mono.setTextSize(24f);
        mono.setColor(lat == null ? TEXT_DIM : TEXT);
        mono.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label, 4, 60, mono);
    }
}
