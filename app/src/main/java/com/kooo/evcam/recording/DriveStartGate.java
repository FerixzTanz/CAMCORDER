package com.kooo.evcam.recording;

import java.util.Map;

/**
 * 上车开录前等一等：车机刚醒、原厂刚用完相机的那几秒不开（CAMCORDER 2026-10-07）。
 *
 * <p>实车两次「开录就卡死」都落在上车那一刻：亮屏、原厂人脸识别拿相机 0 和环视 2 两秒左右、
 * 触屏重新配置（配置变化 0x8）—— 我们在这中间重建环视会话加录像，排不空就卡死了。
 * 所以开录前等：亮屏满 {@link #SCREEN_ON_MS}，人脸识别的相机 0 和环视那一路都空闲（或是我们开着的）
 * 满 {@link #CAMERA_QUIET_MS}。最多等 {@link #MAX_WAIT_MS}，到点照开 —— 宁可晚十几秒，不能不录。</p>
 *
 * <p>纯 Java，见 {@code DriveStartGateTest}。</p>
 */
public final class DriveStartGate {

    public static final long SCREEN_ON_MS = 10_000L;
    public static final long CAMERA_QUIET_MS = 5_000L;
    public static final long MAX_WAIT_MS = 30_000L;
    /** 原厂人脸识别用的座舱相机。 */
    static final String FACE_CAMERA_ID = "0";

    private DriveStartGate() {
    }

    /**
     * @param screenOnForMs 屏幕已经亮了多久；不知道传 -1（不拦）
     * @param cameras       {@code CameraAvailabilityWatch.snapshot()}：每一路 {空闲?1:0, 这个状态持续了多少毫秒, 那时是不是我们}
     * @param surroundId    环视那一路的相机号
     * @param waitedMs      已经等了多久
     */
    public static boolean ready(long screenOnForMs, Map<String, long[]> cameras, String surroundId, long waitedMs) {
        if (waitedMs >= MAX_WAIT_MS) {
            return true;
        }
        if (screenOnForMs >= 0 && screenOnForMs < SCREEN_ON_MS) {
            return false;
        }
        return quiet(cameras, FACE_CAMERA_ID) && quiet(cameras, surroundId);
    }

    private static boolean quiet(Map<String, long[]> cameras, String id) {
        long[] state = id == null || cameras == null ? null : cameras.get(id);
        if (state == null) {
            return true;
        }
        boolean free = state[0] == 1;
        boolean ours = state[2] == 1;
        long forMs = state[1];
        if (!free) {
            // 别人占着：等；我们自己开着：不用等
            return ours;
        }
        return forMs < 0 || forMs >= CAMERA_QUIET_MS;
    }
}
