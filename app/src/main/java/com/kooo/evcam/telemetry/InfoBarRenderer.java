package com.kooo.evcam.telemetry;

import android.content.Context;
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
 *       灭的用实心中灰，不用半透明；没数据的、没启用的（没验证过）都是深灰加一道亮斜杠；
 *       数字加粗、不小于 26 px；
 *       不用虚线、点阵和 1–3 px 的缝。</li>
 *   <li>亮是极氪橙；开着的门、没系的安全带用红；刹车条红、油门条绿（明度也分得开）；
 *       方向盘角度左偏黄、右偏白，不带正负号。</li>
 *   <li>转向灯、双闪一格（项目所有者 2026-10-04）：左箭头 | 双层嵌套三角（同实车双闪键）| 右箭头。
 *       灭 = 灰色空心描边（和自动驻车、哨兵的「灭」同一个画法）；亮 = 近白实心 + 绿色光晕（像实车的转向指示，
 *       和日行灯、远近光的「亮」同一个路数）—— 亮灭靠明度分，去掉颜色也分得开；双闪 = 两只箭头都亮、三角填红。</li>
 *   <li>日行灯那一格以 7X 正脸为底：车身轮廓、星门灯带、徽标、灯带下沿两条白色日行灯线，亮时带光晕。
 *       后灯组以 7X 车尾为底，同一套画法。两格一样宽，图按原来的比例只是居中，不缩不压（项目所有者 2026-10-04）。</li>
 *   <li>灯组靠「哪一块亮、光线朝哪」分状态，不靠颜色深浅：近远光看光线方向，刹车看高位刹车灯那一块，
 *       后雾灯 / 倒车灯各占保险杠上的位置。</li>
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
    /** 尾灯：位置灯暗红，刹车灯、后雾灯亮红。 */
    private static final int TAIL_DIM = 0xFFA8323A;
    private static final int TAIL_BRIGHT = 0xFFFF5A5F;
    /**
     * 转向灯亮时的绿：箭头外沿 3 px 的绿边和向外溢的光晕，芯是 {@link #LAMP_CORE}（项目所有者 2026-10-04：
     * 像实车的转向指示，不用橙）。取明度高的绿（BT.601 亮度 156；芯 246、灭的灰 128、底 44），
     * 压缩、去色之后亮灭照样分得开。
     */
    private static final int INDICATOR = 0xFF22E36B;

    private static final String[] ASSIST_LABELS = {"AEB", "FCW", "LDW", "LKA", "BSD", "RCW"};

    /**
     * 方向盘数字最多多宽（measureText 量的宽，含字两边的留白）：圈的内沿半径 34 - 7/2 = 30.5，
     * 在数字顶上那一高（cy + 11 - 0.711 × 32 = cy - 11.75）宽 2 × √(30.5² - 11.75²) = 56.3。
     * Roboto Bold 的「513」量出来 55.08、实际墨迹约 52，不缩。
     */
    private static final float STEER_DIGIT_ROOM = 56f;

    /** 位图的像素宽（信息条上就是视频宽）。 */
    private final int width;
    /** 逻辑宽度：格子坐标量的是它（信息条上等于 width）。 */
    private final int logicalWidth;
    private final float scale;
    /** 信息条（顶上有一道分隔线）还是车辆状态面板。 */
    private final boolean strip;
    private List<InfoBarLayout.Placed> cells;
    /** 文字格要用字串；车辆状态面板没有文字格，是 null。 */
    private final Context context;
    /** 摆的时候用的勾选版本（{@link InfoBar#selectionVersion}）：变了就重新摆，正在录的下一帧就换。 */
    private int fittedSelection;
    private final Bitmap bitmap;
    private final Canvas canvas;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    /** 双闪里面那只三角（外面那只用 {@link #path}）。 */
    private final Path innerPath = new Path();
    private final RectF rect = new RectF();
    private long lastVersion = -1;
    private boolean drawnOnce;

    /** 录像下面那一条：宽 = 视频宽，高 {@link #HEIGHT}；放哪几格看系统信息里勾的（{@link InfoBar#selection}）。 */
    public InfoBarRenderer(Context context, int width) {
        // 文字格的字按应用语言取（Application 的 Context 在 Android 13 以下是系统语言）
        this(com.kooo.evcam.settings.Languages.localized(context),
                InfoBarLayout.fit(Math.max(2, width), InfoBar.selection(context)),
                Math.max(2, width), HEIGHT, 1f, true);
        fittedSelection = InfoBar.selectionVersion();
    }

    /** 别的摆法（车辆状态面板）：按 scale 画到实际像素上，图标不糊。 */
    public InfoBarRenderer(InfoBarLayout.Arrangement arrangement, float scale) {
        this(null, arrangement.cells, arrangement.width, arrangement.height, scale, false);
    }

    private InfoBarRenderer(Context context, List<InfoBarLayout.Placed> cells, int logicalWidth,
                            int logicalHeight, float scale, boolean strip) {
        this.context = context;
        this.cells = cells;
        this.logicalWidth = logicalWidth;
        this.scale = scale;
        this.strip = strip;
        this.width = Math.max(2, Math.round(logicalWidth * scale));
        this.bitmap = Bitmap.createBitmap(this.width, Math.max(2, Math.round(logicalHeight * scale)),
                Bitmap.Config.ARGB_8888);
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
        boolean refit = false;
        if (strip && context != null) {
            int selection = InfoBar.selectionVersion();
            if (selection != fittedSelection) {
                // 系统信息里改了勾选（或者开发者模式开关了）：重新摆，下一帧就是新的
                cells = InfoBarLayout.fit(logicalWidth, InfoBar.selection(context));
                fittedSelection = selection;
                refit = true;
            }
        }
        if (drawnOnce && !refit && state.version == lastVersion) {
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
        canvas.save();
        canvas.scale(scale, scale);
        if (strip) {
            fill.setColor(DIVIDER);
            canvas.drawRect(0, 0, logicalWidth, 2, fill);
        }
        for (InfoBarLayout.Placed placed : cells) {
            canvas.save();
            canvas.translate(placed.x, placed.y);
            // 没数据的（包括非开发者拿不到的没验证信号）各自按没数据画：斜杠划掉
            if (placed.cell != null) {
                drawCell(placed.cell, s);
            } else {
                drawText(placed.text, placed.width, s.readings);
            }
            canvas.restore();
        }
        canvas.restore();
    }

    private void drawCell(InfoBarLayout.Cell cell, VehicleState s) {
        float cx = cell.width / 2f;
        float cy = HEIGHT / 2f;
        switch (cell) {
            case TURN:
                drawIndicators(cell.width, s.turnSignal, s.hazard);
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
            case PEDALS:
                drawPedals(cell.width, s.brake, s.throttle);
                break;
            case SPEED:
                drawSpeed(cx, cy, s.speedKmh);
                break;
            case AUTO_HOLD:
                drawAutoHold(cx, cy, s.autoHold);
                break;
            case STOCK_360:
                drawStock360(cx, cy, s.stockSurroundShown);
                break;
            case SENTRY:
                drawSentry(cx, cy, s.sentry);
                break;
            case CABIN:
                drawCabin(cx, cy, s.doorsOpen, s.beltsUnbuckled);
                break;
            case DRL:
                drawDaytimeLights(cx, s.daytimeRunningLights);
                break;
            case BEAMS:
                drawBeams(s.lowBeam, s.highBeam);
                break;
            case REAR_LAMPS:
                drawRearLamps(cx, s.rearPositionLamps, s.stopLamps, s.fogLights, s.reverseLamps);
                break;
            case ASSIST:
                drawAssist(new Boolean[]{s.aeb, s.forwardCollisionWarning,
                        s.laneDepartureWarning, s.laneKeepingAid, s.blindSpotAssist, s.rearCollisionWarning});
                break;
            case HORN:
                drawHorn(cx, cy, s.horn);
                break;
            case FLASH:
                drawFlash(s.flashToPass);
                break;
            case ODOMETER:
                drawOdometer(cx, cy, s.odometerKm);
                break;
            case POSITION:
                drawPosition(cx, cy, s.latitude, s.longitude);
                break;
            default:
                break;
        }
    }

    // ================================================================= 通用

    /** 线条的颜色：亮 / 灭 / 没数据。 */
    private static int lineTone(Boolean on) {
        if (on == null) {
            return UNKNOWN_LINE;
        }
        return on ? ON : OFF;
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

    /**
     * 转向灯、双闪一格（184 宽）：左箭头 | 双闪三角 | 右箭头。转向和双闪读的是同一个转向指示状态。
     * 左转 / 右转：那一边的箭头亮绿，三角灰；双闪：两只箭头都亮绿、三角亮红；都不亮：全是灰色空心描边；
     * 两个都读不到才算没数据：箭头、三角都按没数据画，中间一道斜杠。
     */
    private void drawIndicators(int cellWidth, Integer signal, Boolean hazard) {
        boolean unknown = signal == null && hazard == null;
        boolean haz = Boolean.TRUE.equals(hazard);
        Boolean leftOn = unknown ? null : (haz || (signal != null && signal == VehicleState.TURN_LEFT));
        Boolean rightOn = unknown ? null : (haz || (signal != null && signal == VehicleState.TURN_RIGHT));
        float cx = cellWidth / 2f;
        indicatorArrow(cellWidth, true, leftOn);
        indicatorArrow(cellWidth, false, rightOn);
        hazardMark(cx, HEIGHT / 2f, unknown ? null : haz);
        if (unknown) {
            slash(cx - 60, 88, cx + 60, 12);
        }
    }

    /**
     * 一只转向箭头，尖离格边 10：箭头 24 长 46 高，杆 19 长 20 高。
     * 亮 = 绿色光晕 + 6 px 绿边 + 近白的芯（芯盖住绿边的里半，外面露 3 px 绿）；灭 = 灰色空心描边；没数据 = 深灰底浅灰边。
     */
    private void indicatorArrow(int cellWidth, boolean left, Boolean on) {
        float cy = HEIGHT / 2f;
        float tip = left ? 10f : cellWidth - 10f;
        float d = left ? 1f : -1f;  // 从尖往杆的方向
        path.reset();
        path.moveTo(tip, cy);
        path.lineTo(tip + d * 24, cy - 23);
        path.lineTo(tip + d * 24, cy - 10);
        path.lineTo(tip + d * 43, cy - 10);
        path.lineTo(tip + d * 43, cy + 10);
        path.lineTo(tip + d * 24, cy + 10);
        path.lineTo(tip + d * 24, cy + 23);
        path.close();
        if (on == null) {
            fill.setColor(UNKNOWN_FILL);
            canvas.drawPath(path, fill);
            stroke.setColor(UNKNOWN_LINE);
            stroke.setStrokeWidth(4f);
            canvas.drawPath(path, stroke);
        } else if (!on) {
            stroke.setColor(OFF);
            stroke.setStrokeWidth(6f);
            canvas.drawPath(path, stroke);
        } else {
            lampGlow(path, INDICATOR, 8f);
            stroke.setColor(INDICATOR);
            stroke.setStrokeWidth(6f);
            canvas.drawPath(path, stroke);
            fill.setColor(LAMP_CORE);
            canvas.drawPath(path, fill);
        }
    }

    /**
     * 双闪的标志（信息条转向灯那一格和车辆状态面板的双闪格共用），像实车的双闪键：两只同心的正三角，尖朝上，
     * 都是 6 px 的线，线与线之间空 6 px。外面那只：尖 (cx, cy-31)、底边 y cy+29、半宽 34.64（内心 cy+9，内切圆半径 20）；
     * 里面那只是外面那只以内心为中心缩到 0.4。灭 = 两只都是灰色线；亮 = 红色光晕、外面那只填红、两只的线近白；
     * 没数据 = 深灰底浅灰线（斜杠由调用的画）。
     */
    private void hazardMark(float cx, float cy, Boolean on) {
        triangle(path, cx, cy - 31, cy + 29, 34.64f);
        triangle(innerPath, cx, cy - 7, cy + 17, 13.86f);
        if (on == null) {
            fill.setColor(UNKNOWN_FILL);
            canvas.drawPath(path, fill);
            stroke.setColor(UNKNOWN_LINE);
        } else if (!on) {
            stroke.setColor(OFF);
        } else {
            lampGlow(path, WARN, 12f);
            fill.setColor(WARN);
            canvas.drawPath(path, fill);
            stroke.setColor(LAMP_CORE);
        }
        stroke.setStrokeWidth(6f);
        canvas.drawPath(path, stroke);
        canvas.drawPath(innerPath, stroke);
    }

    /** 尖朝上的三角：尖在 (cx, top)，底边在 bottom，半宽 half。 */
    private static void triangle(Path p, float cx, float top, float bottom, float half) {
        p.reset();
        p.moveTo(cx, top);
        p.lineTo(cx + half, bottom);
        p.lineTo(cx - half, bottom);
        p.close();
    }

    /** 车辆状态面板的双闪格（90 宽）：和信息条转向灯那一格中间的三角同一个画法；没数据加一道斜杠。 */
    private void drawHazard(float cx, float cy, Boolean on) {
        hazardMark(cx, cy, on);
        if (on == null) {
            slash(cx - 30, cy + 30, cx + 30, cy - 30);
        }
    }

    /**
     * 亮着的灯向外溢一圈光（和光线、灯块的光晕同一个强度）：形状连同 width 宽的边一起模糊一次 ——
     * 填和描在同一次里画，共用一层模糊。
     */
    private void lampGlow(Path p, int color, float width) {
        glow.setStyle(Paint.Style.FILL_AND_STROKE);
        glow.setStrokeWidth(width);
        glow.setColor(color);
        glow.setAlpha(200);
        glow.setMaskFilter(new BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL));
        canvas.drawPath(p, glow);
        glow.setMaskFilter(null);
    }

    /**
     * 方向盘：整只随转角转；数字居中、度数符号挂在右边；左偏黄、右偏白，不带正负号。
     * 格子 78 宽（宽度是按设计机上的字定的），车机字体更宽时和车速一样缩字：数字的宽超过
     * {@link #STEER_DIGIT_ROOM} 就缩到刚好，数字不压圈；度数符号超出格子右边也缩，不出格。
     * 注意：三位数时度数符号落在圈上（以前 130 宽时也一样），缩半压缩后几乎看不见 —— 怎么改等项目所有者定；
     * 0 / 180 / 360 / 540° 时横辐条和两边的数字有重叠（一直如此）。
     */
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
        float cellWidth = cx * 2f;
        text.setTextSize(32f);
        float tw = text.measureText(digits);
        float size = tw > STEER_DIGIT_ROOM ? 32f * STEER_DIGIT_ROOM / tw : 32f;
        text.setTextSize(size);
        float half = text.measureText(digits) / 2f;
        // 顺带把颜色设好，度数符号用同一个颜色
        centeredText(digits, cx, cy + 11, size, color, text);
        float degX = cx + half + 2f;
        text.setTextSize(20f);
        float dw = text.measureText("°");
        float degRoom = cellWidth - degX;
        if (dw > degRoom) {
            text.setTextSize(20f * degRoom / dw);
        }
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("°", degX, cy, text);
    }

    /** 喇叭：一只号角，右边两道声波。亮填实，灭画轮廓，没数据深灰加斜杠。 */
    private void drawHorn(float cx, float cy, Boolean on) {
        int color = lineTone(on);
        path.reset();
        path.moveTo(cx - 30, cy - 9);
        path.lineTo(cx - 14, cy - 9);
        path.lineTo(cx + 4, cy - 22);
        path.lineTo(cx + 4, cy + 22);
        path.lineTo(cx - 14, cy + 9);
        path.lineTo(cx - 30, cy + 9);
        path.close();
        if (Boolean.TRUE.equals(on)) {
            fill.setColor(ON);
            canvas.drawPath(path, fill);
        } else {
            if (on == null) {
                fill.setColor(UNKNOWN_FILL);
                canvas.drawPath(path, fill);
            }
            stroke.setColor(color);
            stroke.setStrokeWidth(on == null ? 4f : 6f);
            canvas.drawPath(path, stroke);
        }
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        for (int i = 0; i < 2; i++) {
            float r = 14 + i * 12;
            rect.set(cx + 4 - r, cy - r, cx + 4 + r, cy + r);
            canvas.drawArc(rect, -40, 80, false, stroke);
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

    /**
     * 车速（172 宽）：数字靠右对齐在 w-60、「km/h」靠左在 w-52，同一条基线 72。
     * 格宽是按设计机上的字定的，车机字体更宽时缩字：数字只能用 x 0..w-60、「km/h」只能用 x w-52..w，
     * 按原字号量出来超了就按比例缩到刚好 —— 不出格。
     */
    private void drawSpeed(float cx, float cy, Float kmh) {
        float cellWidth = cx * 2f;
        String number = kmh == null ? "--" : String.format(Locale.US, "%d", Math.round(kmh));
        text.setTextSize(64f);
        float room = cellWidth - 60f;
        float tw = text.measureText(number);
        if (tw > room) {
            text.setTextSize(64f * room / tw);
        }
        text.setColor(kmh == null ? TEXT_DIM : TEXT);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(number, cellWidth - 60f, 72f, text);
        text.setTextSize(22f);
        float uw = text.measureText("km/h");
        if (uw > 52f) {
            text.setTextSize(22f * 52f / uw);
        }
        text.setColor(TEXT_DIM);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("km/h", cellWidth - 52f, 72f, text);
        if (kmh == null) {
            slash(cx - 34, cy + 34, cx + 34, cy - 34);
        }
    }

    private void drawAutoHold(float cx, float cy, Boolean on) {
        int color = lineTone(on);
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        canvas.drawCircle(cx, cy, 25, stroke);
        centeredText("A", cx, cy + 12, 32f, color, text);
        if (on == null) {
            slash(cx - 25, cy + 25, cx + 25, cy - 25);
        }
    }

    /**
     * 哨兵模式：一面盾牌。关 = 灰色轮廓；开 = 填实；布防（锁车后）= 填实，中间多一只睁开的眼睛。
     * 开和布防靠「有没有眼睛」分，不靠颜色深浅。没数据深灰加斜杠。
     */
    private void drawSentry(float cx, float cy, Integer code) {
        path.reset();
        path.moveTo(cx, cy - 30);
        path.lineTo(cx + 24, cy - 21);
        path.lineTo(cx + 24, cy + 1);
        path.quadTo(cx + 24, cy + 21, cx, cy + 31);
        path.quadTo(cx - 24, cy + 21, cx - 24, cy + 1);
        path.lineTo(cx - 24, cy - 21);
        path.close();
        boolean on = code != null && code > 0;
        if (code == null) {
            fill.setColor(UNKNOWN_FILL);
            canvas.drawPath(path, fill);
            stroke.setColor(UNKNOWN_LINE);
            stroke.setStrokeWidth(4f);
            canvas.drawPath(path, stroke);
            slash(cx - 30, cy + 30, cx + 30, cy - 30);
            return;
        }
        if (!on) {
            stroke.setColor(OFF);
            stroke.setStrokeWidth(6f);
            canvas.drawPath(path, stroke);
            return;
        }
        fill.setColor(ON);
        canvas.drawPath(path, fill);
        if (code == 2) {
            path.reset();
            path.moveTo(cx - 15, cy);
            path.quadTo(cx, cy - 13, cx + 15, cy);
            path.quadTo(cx, cy + 13, cx - 15, cy);
            path.close();
            fill.setColor(LAMP_CORE);
            canvas.drawPath(path, fill);
            fill.setColor(BG);
            canvas.drawCircle(cx, cy, 5, fill);
        }
    }

    /** 原厂 360 画面：俯视的车，四周四段弧（环视）；显示中就亮。 */
    private void drawStock360(float cx, float cy, Boolean shown) {
        int color = lineTone(shown);
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
     * 亮时两条线带橙色光晕、灯带亮起并向四周散光；灭时整组灰、轮廓还在。
     * 格子 106 宽，和后灯组一样（项目所有者 2026-10-04）：图还是原来为 130 宽的格子画的那张（对称于 x 65，
     * 实际占 x 16..114，两只轮子），整张平移到格子正中，不缩不压 —— 只去掉两边的空白。
     */
    private void drawDaytimeLights(float cx, Boolean on) {
        canvas.save();
        canvas.translate(cx - 65f, 0f);
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
        canvas.restore();
    }

    /**
     * 近光 + 远光一格（90 宽）：右边一个大灯（亮填实、灭画轮廓），左边四道光线，光线的方向说明开的是哪个：
     * 都不亮灰色全斜向下；只近光全斜向下；只远光（含闪远光）全平直；同时开上两道平直、下两道斜向下。
     * 两个都读不到才算没数据；读到一个，另一个按灭画。
     */
    private void drawBeams(Boolean low, Boolean high) {
        boolean lowOn = Boolean.TRUE.equals(low);
        boolean highOn = Boolean.TRUE.equals(high);
        // 平直的光线：只远光四道全平直，近光远光同时开上两道，只近光 / 都不亮没有
        drawHeadlamp(low == null && high == null, lowOn || highOn, highOn ? (lowOn ? 2 : 4) : 0);
    }

    /** 闪远光（车辆状态面板上单独一格，90 宽）：远光的样子，四道光线全平直，闪的时候亮。 */
    private void drawFlash(Boolean flashing) {
        drawHeadlamp(flashing == null, Boolean.TRUE.equals(flashing), 4);
    }

    /** 前灯（90 宽）：半圆灯体 + 四道光线，上面 {@code straight} 道平直、其余斜向下。 */
    private void drawHeadlamp(boolean unknown, boolean lit, int straight) {
        path.reset();
        path.moveTo(60, 20);
        rect.set(34, 20, 86, 80);
        path.arcTo(rect, -90, 180, false);
        path.close();
        if (unknown) {
            fill.setColor(UNKNOWN_FILL);
            canvas.drawPath(path, fill);
            stroke.setColor(UNKNOWN_LINE);
            stroke.setStrokeWidth(4f);
            canvas.drawPath(path, stroke);
        } else if (lit) {
            fill.setColor(ON);
            canvas.drawPath(path, fill);
        } else {
            stroke.setColor(OFF);
            stroke.setStrokeWidth(6f);
            canvas.drawPath(path, stroke);
        }
        int color = unknown ? UNKNOWN_LINE : (lit ? LAMP_CORE : OFF);
        for (int i = 0; i < 4; i++) {
            float y = 28 + i * 14;
            float y1 = i < straight ? y : y + 10;
            if (lit) {
                glowLine(50, y, 12, y1);
            }
            line(50, y, 12, y1, 6f, color);
        }
        if (unknown) {
            slash(14, 86, 84, 14);
        }
    }

    /**
     * 后灯组（106 宽）：以 7X 车尾为底。贯穿尾灯暗红细条 = 后位置灯；亮红粗条 + 高位刹车灯 = 刹车灯；
     * 保险杠两侧各一盏两色灯，外侧亮红 = 后雾灯、内侧白 = 倒车灯，紧挨着。四个都读不到才算没数据。
     * 图还是原来为 150 宽的格子画的那张（对称于 x 75，实际占 x 26..124，两只轮子），整张平移到格子正中，
     * 不缩不压 —— 只去掉两边的空白（项目所有者 2026-10-04：压窄了就不像实车了）。
     */
    private void drawRearLamps(float cx, Boolean position, Boolean stop, Boolean fog, Boolean reverse) {
        canvas.save();
        canvas.translate(cx - 75f, 0f);
        boolean unknown = position == null && stop == null && fog == null && reverse == null;
        boolean braking = Boolean.TRUE.equals(stop);
        boolean fogOn = Boolean.TRUE.equals(fog);
        boolean reversing = Boolean.TRUE.equals(reverse);
        // 车身轮廓、后窗、轮子
        path.reset();
        path.moveTo(30, 86);
        path.lineTo(32, 50);
        path.quadTo(34, 44, 40, 42);
        path.lineTo(52, 22);
        path.quadTo(54, 20, 58, 20);
        path.lineTo(92, 20);
        path.quadTo(96, 20, 98, 22);
        path.lineTo(110, 42);
        path.quadTo(116, 44, 118, 50);
        path.lineTo(120, 86);
        path.close();
        stroke.setColor(unknown ? UNKNOWN_LINE : OUTLINE);
        stroke.setStrokeWidth(5f);
        canvas.drawPath(path, stroke);
        path.reset();
        path.moveTo(46, 42);
        path.lineTo(56, 26);
        path.lineTo(94, 26);
        path.lineTo(104, 42);
        path.close();
        stroke.setColor(unknown ? UNKNOWN_LINE : OFF);
        stroke.setStrokeWidth(4f);
        canvas.drawPath(path, stroke);
        fill.setColor(UNKNOWN_FILL);
        rect.set(26, 84, 44, 92);
        canvas.drawRoundRect(rect, 3, 3, fill);
        rect.set(106, 84, 124, 92);
        canvas.drawRoundRect(rect, 3, 3, fill);
        // 高位刹车灯：只在刹车时亮 —— 刹车靠多出来的这一块认，不靠红的深浅
        if (braking) {
            glowRect(58, 27, 92, 35, 3, TAIL_BRIGHT);
        }
        lamp(60, 28, 90, 34, 2, braking ? TAIL_BRIGHT : UNKNOWN_FILL);
        // 贯穿尾灯
        if (braking) {
            glowRect(32, 48, 118, 64, 6, TAIL_BRIGHT);
            lamp(34, 50, 116, 62, 4, TAIL_BRIGHT);
        } else {
            lamp(34, 52, 116, 59, 3, Boolean.TRUE.equals(position) ? TAIL_DIM : UNKNOWN_FILL);
        }
        // 保险杠两侧：外侧后雾灯、内侧倒车灯
        float[][] sides = {{38, 52}, {98, 84}};
        for (float[] side : sides) {
            float fx = side[0];
            float rx = side[1];
            if (fogOn) {
                glowRect(fx - 2, 68, fx + 16, 82, 4, TAIL_BRIGHT);
            }
            lamp(fx, 70, fx + 14, 80, 3, fogOn ? TAIL_BRIGHT : UNKNOWN_FILL);
            if (reversing) {
                glowRect(rx - 2, 68, rx + 16, 82, 4, LAMP_CORE);
            }
            lamp(rx, 70, rx + 14, 80, 3, reversing ? LAMP_CORE : UNKNOWN_FILL);
        }
        if (unknown) {
            slash(30, 88, 120, 14);
        }
        canvas.restore();
    }

    /** 一块实心的灯。 */
    private void lamp(float l, float t, float r, float b, float radius, int color) {
        rect.set(l, t, r, b);
        fill.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, fill);
    }

    /** 亮着的灯先散一圈光（实心形状下面垫一层模糊）。 */
    private void glowRect(float l, float t, float r, float b, float radius, int color) {
        glow.setStyle(Paint.Style.FILL);
        glow.setColor(color);
        glow.setAlpha(170);
        glow.setMaskFilter(new BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL));
        rect.set(l, t, r, b);
        canvas.drawRoundRect(rect, radius, radius, glow);
        glow.setMaskFilter(null);
    }

    /** 亮着的光线下面垫一道橙色光晕。 */
    private void glowLine(float x0, float y0, float x1, float y1) {
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(11f);
        glow.setColor(ON);
        glow.setAlpha(200);
        glow.setMaskFilter(new BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL));
        canvas.drawLine(x0, y0, x1, y1, glow);
        glow.setMaskFilter(null);
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

    private void drawOdometer(float cx, float cy, Float km) {
        String label = km == null ? "-- km" : String.format(Locale.US, "%d km", Math.round(km));
        mono.setTextSize(28f);
        mono.setColor(km == null ? TEXT_DIM : TEXT);
        mono.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label, 4, 61, mono);
        if (km == null) {
            slash(cx - 28, cy + 28, cx + 28, cy - 28);
        }
    }

    /**
     * 文字格：没有图标的信号（项目所有者 2026-10-03：先用文字，要的话之后再画）。
     * 上面一行名称（小、灰），下面一行值（大、白），写法和系统信息页一样（{@link SignalText}）；
     * 没数据是「--」加斜杠，和别的格一个规矩。放不下的截掉，末尾一个省略号。
     */
    private void drawText(Signal signal, int cellWidth, Readings readings) {
        if (context == null) {
            return;
        }
        Object value = readings == null ? null : readings.get(signal);
        float room = cellWidth - 8;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(22f);
        text.setColor(TEXT_DIM);
        canvas.drawText(fit(context.getString(signal.labelRes), room, text), 4, 36, text);
        text.setTextSize(30f);
        text.setColor(value == null ? TEXT_DIM : TEXT);
        canvas.drawText(fit(value == null ? "--" : SignalText.text(context, signal, value), room, text),
                4, 76, text);
        if (value == null) {
            float cx = cellWidth / 2f;
            float cy = HEIGHT / 2f;
            slash(cx - 28, cy + 28, cx + 28, cy - 28);
        }
    }

    /** 放得下就原样，放不下截短，末尾一个省略号。 */
    private static String fit(String s, float room, Paint paint) {
        if (paint.measureText(s) <= room) {
            return s;
        }
        int end = s.length();
        while (end > 1 && paint.measureText(s, 0, end) + paint.measureText("…") > room) {
            end--;
        }
        return s.substring(0, end) + "…";
    }

    /** 经纬度：两行，纬度在上。 */
    private void drawPosition(float cx, float cy, Double lat, Double lon) {
        boolean known = lat != null && lon != null;
        mono.setTextSize(26f);
        mono.setColor(known ? TEXT : TEXT_DIM);
        mono.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(known ? String.format(Locale.US, "%.6f", lat) : "--", 4, 44, mono);
        canvas.drawText(known ? String.format(Locale.US, "%.6f", lon) : "--", 4, 78, mono);
        if (!known) {
            slash(cx - 28, cy + 28, cx + 28, cy - 28);
        }
    }
}
