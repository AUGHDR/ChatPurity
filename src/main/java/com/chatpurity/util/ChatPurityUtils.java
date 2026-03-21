package com.chatpurity.util;

/**
 * ChatPurity 工具类
 * 
 * <p>提供通用的工具方法，避免代码重复。
 * 该类为最终类，不允许实例化。
 * 
 * @see ChatPurityMod 主模组类
 */
public final class ChatPurityUtils {
    
    /**
     * 私有构造函数，防止实例化
     */
    private ChatPurityUtils() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }
    
    /**
     * 格式化秒数为可读时间
     * 
     * <p>自动选择合适的单位进行格式化：
     * <ul>
     *   <li>小于 60 秒：显示秒</li>
     *   <li>小于 1 小时：显示分钟（和秒）</li>
     *   <li>小于 1 天：显示小时（和分钟）</li>
     *   <li>大于等于 1 天：显示天（和小时）</li>
     * </ul>
     * 
     * @param seconds 秒数（不能为负数）
     * @return 格式化的时间字符串，例如 "5分钟30秒"、"2小时30分钟"、"3天5小时"
     */
    public static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "0秒";
        }
        
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return secs > 0 ? minutes + "分钟" + secs + "秒" : minutes + "分钟";
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return minutes > 0 ? hours + "小时" + minutes + "分钟" : hours + "小时";
        } else {
            long days = seconds / 86400;
            long hours = (seconds % 86400) / 3600;
            return hours > 0 ? days + "天" + hours + "小时" : days + "天";
        }
    }
    
    /**
     * 检查字符串是否为空或空白
     * @param str 字符串
     * @return true 表示为空或空白
     */
    public static boolean isNullOrBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    /**
     * 安全地获取字符串的默认值
     * 
     * <p>如果字符串为 null 或空白（仅包含空白字符），则返回默认值。
     * 
     * @param str 要检查的字符串
     * @param defaultValue 默认值，当 str 为空或空白时返回
     * @return 非空字符串或默认值
     */
    public static String getOrDefault(String str, String defaultValue) {
        return isNullOrBlank(str) ? defaultValue : str;
    }
    
    /**
     * 解析时间字符串为秒数
     * 
     * <p>支持的时间格式：
     * <ul>
     *   <li>纯数字：秒数</li>
     *   <li>数字 + "s"：秒</li>
     *   <li>数字 + "min"：分钟</li>
     *   <li>数字 + "h"：小时</li>
     *   <li>数字 + "d"：天</li>
     *   <li>"-1"：表示永久</li>
     * </ul>
     * 
     * @param timeStr 时间字符串
     * @return 秒数，如果解析失败返回 -1
     */
    public static long parseTime(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        
        timeStr = timeStr.trim().toLowerCase();
        
        if ("-1".equals(timeStr)) {
            return -1; // 表示永久
        }
        
        try {
            long value;
            long result;
            
            if (timeStr.matches("^\\d+$")) {
                // 纯数字格式，单位为秒
                value = Long.parseLong(timeStr);
                result = value;
            } else if (timeStr.endsWith("s")) {
                // 秒格式：10s, 30s
                String numberPart = timeStr.substring(0, timeStr.length() - 1);
                if (numberPart.isEmpty()) {
                    return -1;
                }
                value = Long.parseLong(numberPart);
                result = value;
            } else if (timeStr.endsWith("min")) {
                // 分钟格式：5min, 10min
                String numberPart = timeStr.substring(0, timeStr.length() - 3);
                if (numberPart.isEmpty()) {
                    return -1;
                }
                value = Long.parseLong(numberPart);
                // 溢出保护：检查乘法是否会溢出
                if (value > Long.MAX_VALUE / 60) {
                    return Long.MAX_VALUE / 1000; // 返回最大安全值
                }
                result = value * 60;
            } else if (timeStr.endsWith("h")) {
                // 小时格式：2h, 24h
                String numberPart = timeStr.substring(0, timeStr.length() - 1);
                if (numberPart.isEmpty()) {
                    return -1;
                }
                value = Long.parseLong(numberPart);
                // 溢出保护
                if (value > Long.MAX_VALUE / 3600) {
                    return Long.MAX_VALUE / 1000;
                }
                result = value * 3600;
            } else if (timeStr.endsWith("d")) {
                // 天格式：1d, 7d
                String numberPart = timeStr.substring(0, timeStr.length() - 1);
                if (numberPart.isEmpty()) {
                    return -1;
                }
                value = Long.parseLong(numberPart);
                // 溢出保护
                if (value > Long.MAX_VALUE / 86400) {
                    return Long.MAX_VALUE / 1000;
                }
                result = value * 86400;
            } else {
                // 不支持的格式
                return -1;
            }
            
            // 最终溢出检查：确保结果在合理范围内
            // 最大约 68 年的秒数，足够大且不会导致毫秒计算溢出
            if (result > Long.MAX_VALUE / 1000) {
                return Long.MAX_VALUE / 1000;
            }
            
            return result;
        } catch (NumberFormatException e) {
            // 解析失败，返回-1表示无效时间
            return -1;
        } catch (IndexOutOfBoundsException e) {
            // 字符串索引越界，返回-1
            return -1;
        }
    }
    
    /**
     * 安全地计算禁言结束时间
     * 
     * @param seconds 禁言秒数
     * @return 禁言结束时间戳（毫秒），如果溢出则返回 Long.MAX_VALUE
     */
    public static long calculateMuteEndTime(long seconds) {
        if (seconds < 0) {
            return Long.MAX_VALUE; // 永久禁言
        }
        
        long currentTime = System.currentTimeMillis();
        long millisToAdd = seconds * 1000L;
        
        // 溢出检查
        if (millisToAdd / 1000 != seconds) {
            return Long.MAX_VALUE;
        }
        
        long endTime = currentTime + millisToAdd;
        if (endTime < currentTime) {
            // 溢出发生
            return Long.MAX_VALUE;
        }
        
        return endTime;
    }
}
