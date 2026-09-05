package com.yourname.devpulse.services;

import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;

/**
 * 本地缓存，用于计算今日 Star 增量。
 * 将 totalStars 与日期(yyyy-MM-dd)存入应用级 PropertiesComponent，
 * 每次拉取新数据时对比基线计算 todayDelta。
 * <p>
 * Cache key 按用户名隔离（app-level），不同用户的数据不会互相干扰。
 */
public final class DataCacheService {
    private static final String KEY_PREFIX_DATE = "devpulse.cache.";
    private static final String KEY_SUFFIX_DATE = ".lastDate";
    private static final String KEY_SUFFIX_STARS = ".lastTotalStars";

    private DataCacheService() {
    }

    /**
     * 计算今日增量并更新缓存基线。
     *
     * @param username       GitHub 用户名，用于隔离缓存 Key
     * @param currentTotalStars 当前总 Star 数
     * @return currentTotalStars - 基线值（跨天时为昨日总量，同天时为今日首次记录值）
     */
    public static int computeAndStoreDelta(@NotNull String username, int currentTotalStars) {
        // 应用级存储（无参），不再依赖 Project 实例
        PropertiesComponent pc = PropertiesComponent.getInstance();
        String dateKey = buildKey(username, KEY_SUFFIX_DATE);
        String starsKey = buildKey(username, KEY_SUFFIX_STARS);

        String today = LocalDate.now().toString();
        String lastDate = pc.getValue(dateKey);
        String lastStarsStr = pc.getValue(starsKey);

        int baseline;
        if (!today.equals(lastDate)) {
            // 跨天或首次运行：旧快照视为上一日的总量，作为今天增量的基线
            baseline = parseInt(lastStarsStr, currentTotalStars);
            // 用当前值刷新基线，供今天后续调用对比
            pc.setValue(dateKey, today);
            pc.setValue(starsKey, String.valueOf(currentTotalStars));
        } else {
            baseline = parseInt(lastStarsStr, currentTotalStars);
        }

        return currentTotalStars - baseline;
    }

    private static String buildKey(String username, String suffix) {
        return KEY_PREFIX_DATE + username + suffix;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
