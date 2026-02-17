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
     * @return 格式化的时间字符串，例如 "5分钟"、"2小时30分钟"、"3天5小时"
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
            return secs > 0 ? minutes + "分" + secs + "秒" : minutes + "分钟";
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
}
