package com.kooo.evcam.playback;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 扫照片目录：同一秒拍的几路归成一组（{@link PhotoGroup}），再按日期分段，最新的在前。
 *
 * <p>回看网格（GalleryActivity）和照片查看器（PhotoPlaybackActivity）用的是同一份扫描，
 * 两边看到的分组才一致。只读目录，不碰界面，可以在后台线程调。</p>
 */
public final class PhotoScan {

    private PhotoScan() {
    }

    /** @return 按日期分好的组；目录不存在或没有照片时是空列表 */
    public static List<DateSection<PhotoGroup>> scan(File dir) {
        List<DateSection<PhotoGroup>> sections = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return sections;
        }
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.US);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
        });
        if (files == null || files.length == 0) {
            return sections;
        }

        // 第一步：按时间戳分组（同一秒拍摄的多路图片）
        Map<String, PhotoGroup> groupMap = new HashMap<>();
        for (File file : files) {
            String timestamp = PhotoGroup.extractTimestampPrefix(file.getName());
            PhotoGroup group = groupMap.get(timestamp);
            if (group == null) {
                group = new PhotoGroup(timestamp);
                groupMap.put(timestamp, group);
            }
            group.addFile(file);
        }

        // 最新的在前
        List<PhotoGroup> allGroups = new ArrayList<>(groupMap.values());
        Collections.sort(allGroups, (g1, g2) -> g2.getCaptureTime().compareTo(g1.getCaptureTime()));

        // 第二步：按日期分段（LinkedHashMap 保持插入顺序，而 allGroups 已排序）
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Map<String, DateSection<PhotoGroup>> byDate = new LinkedHashMap<>();
        for (PhotoGroup group : allGroups) {
            String dateString = dateFormat.format(group.getCaptureTime());
            DateSection<PhotoGroup> section = byDate.get(dateString);
            if (section == null) {
                section = new DateSection<>(dateString, group.getCaptureTime());
                byDate.put(dateString, section);
            }
            section.addItem(group);
        }
        sections.addAll(byDate.values());
        return sections;
    }
}
