package com.chatpurity.handler;

import com.chatpurity.config.ChatPurityConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * 聊天处理器 - 核心过滤逻辑
 * 
 * <p>负责处理所有聊天消息的过滤和转换逻辑，包括：
 * <ul>
 *   <li>权限豁免检查 - 检查玩家是否在豁免列表或拥有 OP 权限</li>
 *   <li>白名单检查 - 包含白名单词汇的消息豁免过滤</li>
 *   <li>黑名单检查 - 检测并屏蔽包含敏感词的消息</li>
 *   <li>单词黑名单检查 - 检测由特定字符组成的刷屏消息</li>
 *   <li>夹杂词黑名单检查 - 检测被分隔符拆分的敏感词</li>
 *   <li>URL 过滤 - 检测并屏蔽包含链接的消息</li>
 *   <li>消息长度限制 - 屏蔽过长的消息</li>
 *   <li>敏感词转换 - 将敏感词替换为指定内容</li>
 *   <li>同类词转换 - 将同类字符组成的刷屏消息优化显示</li>
 *   <li>重复合并 - 将重复内容合并显示</li>
 * </ul>
 * 
 * @see ChatPurityConfig 配置管理
 */
public class ChatHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    private final ChatPurityConfig config;

    /**
     * 构造聊天处理器
     * @param config 配置实例
     */
    public ChatHandler(ChatPurityConfig config) {
        this.config = config;
    }
    
    /**
     * 检查玩家是否豁免过滤
     * @param player 玩家
     * @param server 服务器实例
     * @return true 表示豁免
     */
    public boolean isBypassed(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) {
            return false;
        }
        
        // 检查玩家名是否在豁免列表中
        String playerName = player.getName().getString();
        for (String bypassPlayer : config.getBypassPlayers()) {
            if (config.isIgnoreCase()) {
                if (playerName.equalsIgnoreCase(bypassPlayer)) {
                    if (config.isDebugMode()) {
                        LOGGER.debug("Player {} bypassed filter (in bypass list)", playerName);
                    }
                    return true;
                }
            } else {
                if (playerName.equals(bypassPlayer)) {
                    if (config.isDebugMode()) {
                        LOGGER.debug("Player {} bypassed filter (in bypass list)", playerName);
                    }
                    return true;
                }
            }
        }
        
        // 检查玩家权限等级是否达到豁免等级
        int requiredLevel = config.getBypassPermissionLevel();
        if (requiredLevel <= 0) {
            return false; // 等级 0 表示没人豁免
        }
        
        // 检查玩家是否为 OP
        PlayerConfigEntry configEntry = new PlayerConfigEntry(player.getGameProfile());
        boolean isOp = server.getPlayerManager().isOperator(configEntry);
        
        if (isOp && config.isDebugMode()) {
            LOGGER.debug("Player {} bypassed filter (has OP)", playerName);
        }
        return isOp;
    }
    
    /**
     * 检查消息是否应该被屏蔽（用于 ALLOW_CHAT_MESSAGE 事件）
     * @param originalText 原始消息文本
     * @param player 发送者
     * @param server 服务器实例
     * @return true 表示应该屏蔽
     */
    public boolean shouldBlock(Text originalText, ServerPlayerEntity player, MinecraftServer server) {
        // 总开关检查
        if (!config.isEnableFilter()) {
            return false;
        }
        
        // 权限豁免检查
        if (isBypassed(player, server)) {
            if (config.isDebugMode()) {
                LOGGER.debug("Player {} bypassed filter", player.getName().getString());
            }
            return false;
        }
        
        String message = originalText.getString();
        
        // 消息长度限制检查
        if (config.isEnableLengthLimit() && message.length() > config.getMaxMessageLength()) {
            if (config.isDebugMode()) {
                LOGGER.debug("Message too long: {} characters", message.length());
            }
            return true;
        }
        
        // URL 过滤检查
        if (config.isBlockUrls() && containsUrl(message)) {
            if (config.isDebugMode()) {
                LOGGER.debug("Message contains URL: {}", message);
            }
            return true;
        }
        
        // 白名单检查
        if (config.isEnableWhitelist() && isWhitelisted(message)) {
            if (config.isDebugMode()) {
                LOGGER.debug("Message whitelisted: {}", message);
            }
            return false;
        }

        // 黑名单检查
        if (config.isEnableBlacklist() && isBlacklisted(message)) {
            if (config.isDebugMode()) {
                LOGGER.debug("Message blacklisted: {}", message);
            }
            if (config.isEnableReleaseCompliant()) {
                // 合规释放模式：替换敏感词而非屏蔽整条消息
                return false; // 不屏蔽，让 applyConversions 处理
            }
            return true;
        }

        // 单词黑名单检查
        if (config.isEnableWordBlacklist() && isWordBlacklisted(message)) {
            if (config.isDebugMode()) {
                LOGGER.debug("Message word-blacklisted: {}", message);
            }
            if (config.isEnableReleaseCompliant()) {
                // 合规释放模式：不屏蔽
                return false;
            }
            return true;
        }

        // 夹杂词黑名单检查
        if (config.isEnableMixedBlacklist() && isMixedBlacklisted(message)) {
            if (config.isDebugMode()) {
                LOGGER.debug("Message mixed-blacklisted: {}", message);
            }
            if (config.isEnableReleaseCompliant()) {
                // 合规释放模式：不屏蔽
                return false;
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * 应用转换词
     * 
     * <p>处理流程：
     * <ol>
     *   <li>合规释放模式下替换黑名单词</li>
     *   <li>同类词转换</li>
     *   <li>重复合并</li>
     *   <li>转换词替换</li>
     * </ol>
     * 
     * @param message 原始消息
     * @return 转换后的消息
     */
    public String applyConversions(String message) {
        // 检查是否启用转换功能
        if (!config.isEnableFilter() || !config.isEnableConversions()) {
            return message;
        }
        
        String result = message;
        
        // 合规释放模式：优先替换黑名单词
        if (config.isEnableReleaseCompliant()) {
            result = replaceBlacklistedWords(result);
        }
        
        // 同类词转换功能
        if (config.isEnableSameClassConversion()) {
            String sameClassResult = applySameClassConversion(result);
            if (!sameClassResult.equals(result)) {
                result = sameClassResult;
            }
        }
        
        // 重复合并功能
        if (config.isEnableRepeatMerge()) {
            result = mergeRepeatedContent(result);
        }
        
        // 转换词替换
        for (Map.Entry<String, String> entry : config.getConversions().entrySet()) {
            String from = entry.getKey();
            String to = entry.getValue();
            
            if (config.isIgnoreCase()) {
                // 不区分大小写替换
                result = replaceIgnoreCase(result, from, to);
            } else {
                if (result.contains(from)) {
                    result = result.replace(from, to);
                }
            }
        }
        return result;
    }
    
    /**
     * 合规释放模式：替换黑名单词为指定内容
     * @param message 原始消息
     * @return 替换后的消息
     */
    private String replaceBlacklistedWords(String message) {
        String result = message;
        String defaultReplacement = config.getReleaseCompliantReplacement();
        
        // 黑名单替换
        if (config.isEnableBlacklist()) {
            for (String blacklistItem : config.getBlacklist()) {
                // 检查是否有自定义替换规则
                String replacement = defaultReplacement;
                for (Map.Entry<String, String> entry : config.getCustomReplacements().entrySet()) {
                    if (blacklistItem.contains(entry.getKey()) || entry.getKey().contains(blacklistItem)) {
                        replacement = entry.getValue();
                        break;
                    }
                }
                
                if (config.isEnableRegex()) {
                    try {
                        Pattern regex;
                        if (config.isIgnoreCase()) {
                            regex = Pattern.compile(blacklistItem, Pattern.CASE_INSENSITIVE);
                        } else {
                            regex = Pattern.compile(blacklistItem);
                        }
                        result = regex.matcher(result).replaceAll(replacement);
                    } catch (Exception e) {
                        // 正则表达式无效，使用普通替换
                        result = replaceAll(result, blacklistItem, replacement);
                    }
                } else {
                    result = replaceAll(result, blacklistItem, replacement);
                }
            }
        }
        
        // 夹杂词黑名单替换
        if (config.isEnableMixedBlacklist()) {
            for (List<String> group : config.getMixedBlacklistGroups()) {
                for (String item : group) {
                    // 检查是否有自定义替换规则
                    String replacement = defaultReplacement;
                    for (Map.Entry<String, String> entry : config.getCustomReplacements().entrySet()) {
                        if (item.contains(entry.getKey()) || entry.getKey().contains(item)) {
                            replacement = entry.getValue();
                            break;
                        }
                    }
                    result = replaceAll(result, item, replacement);
                }
            }
        }
        
        return result;
    }
    
    /**
     * 不区分大小写的全部替换
     * @param source 源字符串
     * @param target 目标字符串
     * @param replacement 替换字符串
     * @return 替换后的字符串
     */
    private String replaceAll(String source, String target, String replacement) {
        if (source == null || target == null || target.isEmpty()) {
            return source;
        }
        
        StringBuilder sb = new StringBuilder();
        int index = 0;
        int targetLength = target.length();
        String lowerSource = source.toLowerCase();
        String lowerTarget = target.toLowerCase();
        
        int foundIndex;
        while ((foundIndex = lowerSource.indexOf(lowerTarget, index)) != -1) {
            sb.append(source, index, foundIndex);
            sb.append(replacement);
            index = foundIndex + targetLength;
        }
        sb.append(source.substring(index));
        
        return sb.toString();
    }
    
    /**
     * 不区分大小写的字符串替换（替换所有匹配项）
     * @param source 源字符串
     * @param target 目标字符串
     * @param replacement 替换字符串
     * @return 替换后的字符串
     */
    private String replaceIgnoreCase(String source, String target, String replacement) {
        if (source == null || target == null || target.isEmpty()) {
            return source;
        }
        
        StringBuilder sb = new StringBuilder();
        int index = 0;
        int targetLength = target.length();
        String lowerSource = source.toLowerCase();
        String lowerTarget = target.toLowerCase();
        
        int foundIndex;
        while ((foundIndex = lowerSource.indexOf(lowerTarget, index)) != -1) {
            sb.append(source, index, foundIndex);
            sb.append(replacement);
            index = foundIndex + targetLength;
        }
        sb.append(source.substring(index));
        
        return sb.toString();
    }
    
    /**
     * 检查消息是否包含白名单词汇
     * @param message 消息内容
     * @return true 表示包含白名单词汇
     */
    private boolean isWhitelisted(String message) {
        if (config.isIgnoreCase()) {
            String lowerMessage = message.toLowerCase();
            for (String whitelistItem : config.getWhitelist()) {
                if (lowerMessage.contains(whitelistItem.toLowerCase())) {
                    return true;
                }
            }
        } else {
            for (String whitelistItem : config.getWhitelist()) {
                if (message.contains(whitelistItem)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 检查消息是否包含黑名单词汇
     * <p>支持普通字符串匹配和正则表达式匹配
     * @param message 消息内容
     * @return true 表示包含黑名单词汇
     */
    private boolean isBlacklisted(String message) {
        if (config.isEnableRegex()) {
            // 使用正则表达式匹配
            for (String pattern : config.getBlacklist()) {
                try {
                    Pattern regex;
                    if (config.isIgnoreCase()) {
                        regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                    } else {
                        regex = Pattern.compile(pattern);
                    }
                    Matcher matcher = regex.matcher(message);
                    if (matcher.find()) {
                        return true;
                    }
                } catch (Exception e) {
                    // 正则表达式无效，使用普通匹配
                    if (config.isDebugMode()) {
                        LOGGER.warn("Invalid regex pattern: {}", pattern);
                    }
                    if (config.isIgnoreCase()) {
                        if (message.toLowerCase().contains(pattern.toLowerCase())) {
                            return true;
                        }
                    } else {
                        if (message.contains(pattern)) {
                            return true;
                        }
                    }
                }
            }
        } else {
            // 普通字符串匹配
            if (config.isIgnoreCase()) {
                String lowerMessage = message.toLowerCase();
                for (String blacklistItem : config.getBlacklist()) {
                    if (lowerMessage.contains(blacklistItem.toLowerCase())) {
                        return true;
                    }
                }
            } else {
                for (String blacklistItem : config.getBlacklist()) {
                    if (message.contains(blacklistItem)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * 检查消息是否完全由单词黑名单中的字符组成
     * <p>用于检测如 "啊啊啊啊啊" 这样的刷屏消息
     * @param message 消息内容
     * @return true 表示所有字符都在单词黑名单中
     */
    private boolean isWordBlacklisted(String message) {
        // 收集消息中所有的字/词（忽略空格和标点）
        Set<String> messageChars = new HashSet<>();
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            // 跳过空格和标点符号
            if (Character.isWhitespace(c) || isPunctuation(c)) {
                continue;
            }
            if (config.isIgnoreCase()) {
                messageChars.add(String.valueOf(c).toLowerCase());
            } else {
                messageChars.add(String.valueOf(c));
            }
        }
        
        if (messageChars.isEmpty()) {
            return false;
        }
        
        // 检查消息中的所有字是否都在单词黑名单中
        for (String charStr : messageChars) {
            boolean found = false;
            for (String blacklistedWord : config.getWordBlacklist()) {
                String compareWord = config.isIgnoreCase() ? blacklistedWord.toLowerCase() : blacklistedWord;
                if (compareWord.equalsIgnoreCase(charStr)) {
                    found = true;
                    break;
                }
            }
            // 只要发现一个不在黑名单中的字，就不屏蔽
            if (!found) {
                return false;
            }
        }
        
        // 所有字都在黑名单中，屏蔽
        return true;
    }
    
    /**
     * 检查消息是否包含夹杂词黑名单组中的所有字/词
     * @param message 消息
     * @return true 表示应该屏蔽
     */
    private boolean isMixedBlacklisted(String message) {
        // 获取阈值
        int threshold = config.getMixedBlacklistThreshold();
        
        // 遍历每个夹杂词黑名单组
        for (List<String> group : config.getMixedBlacklistGroups()) {
            if (group.isEmpty()) {
                continue;
            }
            
            // 如果阈值为 0，使用组大小作为阈值（必须包含所有字/词）
            int requiredCount = (threshold == 0) ? group.size() : Math.min(threshold, group.size());
            
            // 统计消息中包含组内多少个字/词
            int foundCount = 0;
            for (String item : group) {
                boolean found = false;
                if (config.isIgnoreCase()) {
                    if (message.toLowerCase().contains(item.toLowerCase())) {
                        found = true;
                    }
                } else {
                    if (message.contains(item)) {
                        found = true;
                    }
                }
                if (found) {
                    foundCount++;
                }
            }
            
            // 如果找到的数量达到阈值，屏蔽
            if (foundCount >= requiredCount) {
                if (config.isDebugMode()) {
                    LOGGER.debug("Mixed blacklist group matched: {} (found {}/{})", group, foundCount, requiredCount);
                }
                return true;
            }

            // 乱序检测：如果启用乱序检测，检查所有字/词是否都存在（不按顺序）
            if (config.isEnableMixedDisorderDetection() && foundCount == group.size()) {
                if (config.isDebugMode()) {
                    LOGGER.debug("Mixed blacklist group matched (disorder): {}", group);
                }
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查字符是否为标点符号
     * <p>支持英文和中文标点
     * @param c 字符
     * @return true 表示是标点符号
     */
    private boolean isPunctuation(char c) {
        // 英文标点
        if (c == ',' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':' || 
            c == '"' || c == '\'' || c == '(' || c == ')' || c == '[' || c == ']' || 
            c == '{' || c == '}' || c == '-') {
            return true;
        }
        // 中文标点（使用 Unicode 转义避免编码问题）
        if (c == '\u3001' || c == '\u3002' || c == '\uff01' || c == '\uff1f' || 
            c == '\uff1b' || c == '\uff1a' ||  // 、。！？；：
            c == '\u201c' || c == '\u201d' || c == '\u2018' || c == '\u2019' ||  // ""''
            c == '\uff08' || c == '\uff09' || c == '\u3010' || c == '\u3011' ||  // （）【】
            c == '\uff5b' || c == '\uff5d' || c == '\u2014' || c == '\u2026') {  // ｛｝—…
            return true;
        }
        return false;
    }
    
    /**
     * 检测消息是否包含 URL
     * @param message 消息内容
     * @return true 表示包含 URL
     */
    private boolean containsUrl(String message) {
        if (!config.isBlockUrls()) {
            return false;
        }
        
        // URL 白名单检查
        List<String> urlWhitelist = config.getUrlWhitelist();
        if (urlWhitelist != null && !urlWhitelist.isEmpty()) {
            for (String allowedDomain : urlWhitelist) {
                if (message.toLowerCase().contains(allowedDomain.toLowerCase())) {
                    return false; // 在白名单中，不屏蔽
                }
            }
        }
        
        // URL 正则表达式匹配
        String urlPattern = "(https?://|www\\.)[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+([/?][^\\s]*)?";
        return message.toLowerCase().matches(".*" + urlPattern + ".*");
    }
    
    /**
     * 应用同类词转换
     * @param message 原始消息
     * @return 转换后的消息
     */
    private String applySameClassConversion(String message) {
        if (message == null || message.length() < config.getSameClassMinLength()) {
            return message;
        }
        
        // 分析消息中的字符类型
        Map<String, Integer> categoryCounts = new HashMap<>();
        int totalChars = 0;
        
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            String category = getCharCategory(c);
            
            if (!category.equals("other")) {
                categoryCounts.put(category, categoryCounts.getOrDefault(category, 0) + 1);
                totalChars++;
            }
        }
        
        if (totalChars == 0) {
            return message;
        }
        
        // 检查是否某一类字符占比超过阈值
        for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
            double ratio = (double) entry.getValue() / totalChars;
            if (ratio >= config.getSameClassThreshold()) {
                // 触发同类词转换
                return config.getSameClassReplacement();
            }
        }
        
        return message;
    }
    
    /**
     * 获取字符所属的类别
     * @param c 字符
     * @return 类别名称
     */
    private String getCharCategory(char c) {
        // 检查自定义分类
        Map<String, List<String>> customCategories = config.getSameClassCategories();
        if (customCategories != null) {
            for (Map.Entry<String, List<String>> entry : customCategories.entrySet()) {
                for (String pattern : entry.getValue()) {
                    if (String.valueOf(c).equals(pattern)) {
                        return entry.getKey();
                    }
                }
            }
        }
        
        // 默认分类
        if (Character.isLetter(c)) {
            // 检查是否为中文字符
            if (isChineseChar(c)) {
                return "chinese";
            }
            return "english";
        } else if (Character.isDigit(c)) {
            return "number";
        } else if (isChineseSpamChar(c)) {
            return "chinese_spam";
        } else {
            return "other";
        }
    }
    
    /**
     * 判断是否为中文字符
     * @param c 字符
     * @return true 表示是中文字符
     */
    private boolean isChineseChar(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) ||
               (c >= 0x3400 && c <= 0x4DBF) ||
               (c >= 0x20000 && c <= 0x2EBEF);
    }
    
    /**
     * 判断是否为中文刷屏字符
     * <p>常见刷屏字符如：啊、哈、呵、嘿、哇、哦、呀
     * @param c 字符
     * @return true 表示是刷屏字符
     */
    private boolean isChineseSpamChar(char c) {
        // 常见刷屏字符
        String spamChars = "啊哈呵嘿哇哦呀";
        return spamChars.indexOf(c) >= 0;
    }
    
    /**
     * 合并重复内容
     * @param message 原始消息
     * @return 合并后的消息
     */
    private String mergeRepeatedContent(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        
        int minLength = config.getPhraseMinLength();
        int maxLength = config.getPhraseMaxLength();
        int minCount = config.getRepeatMergeMinCount();
        int maxDisplay = config.getRepeatMergeMaxDisplay();
        boolean enablePhrase = config.isEnablePhraseRepeatDetection();
        
        String result = message;
        
        // 首先检测短语重复（多字符重复）
        if (enablePhrase) {
            result = mergePhraseRepeats(result, minLength, maxLength, minCount, maxDisplay);
        }
        
        // 然后检测单字符重复
        result = mergeCharRepeats(result, minCount, maxDisplay);
        
        return result;
    }
    
    /**
     * 合并短语重复
     * <p>将重复的短语合并为 "内容(×次数)" 格式
     * @param message 消息内容
     * @param minLength 短语最小长度
     * @param maxLength 短语最大长度
     * @param minCount 触发合并的最小重复次数
     * @param maxDisplay 最大显示次数
     * @return 合并后的消息
     */
    private String mergePhraseRepeats(String message, int minLength, int maxLength, int minCount, int maxDisplay) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        
        while (i < message.length()) {
            boolean foundRepeat = false;
            
            // 从最大长度开始检测
            for (int len = Math.min(maxLength, message.length() - i); len >= minLength; len--) {
                String candidate = message.substring(i, i + len);
                int count = 1;
                int j = i + len;
                
                // 计算重复次数
                while (j + len <= message.length() && message.substring(j, j + len).equals(candidate)) {
                    count++;
                    j += len;
                }
                
                if (count >= minCount) {
                    // 找到重复，合并
                    int displayCount = Math.min(count, maxDisplay);
                    String format;
                    if (count > maxDisplay) {
                        format = config.getRepeatMergeOverflowFormat()
                            .replace("{content}", candidate)
                            .replace("{countPlus}", String.valueOf(displayCount));
                    } else {
                        format = config.getRepeatMergeFormat()
                            .replace("{content}", candidate)
                            .replace("{count}", String.valueOf(count));
                    }
                    result.append(format);
                    i = j;
                    foundRepeat = true;
                    break;
                }
            }
            
            if (!foundRepeat) {
                result.append(message.charAt(i));
                i++;
            }
        }
        
        return result.toString();
    }
    
    /**
     * 合并单字符重复
     * <p>将重复的单字符合并为 "字符(×次数)" 格式
     * @param message 消息内容
     * @param minCount 触发合并的最小重复次数
     * @param maxDisplay 最大显示次数
     * @return 合并后的消息
     */
    private String mergeCharRepeats(String message, int minCount, int maxDisplay) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        
        while (i < message.length()) {
            char c = message.charAt(i);
            int count = 1;
            
            // 计算连续重复次数
            while (i + count < message.length() && message.charAt(i + count) == c) {
                count++;
            }
            
            if (count >= minCount) {
                // 找到重复，合并
                int displayCount = Math.min(count, maxDisplay);
                String format;
                if (count > maxDisplay) {
                    format = config.getRepeatMergeOverflowFormat()
                        .replace("{content}", String.valueOf(c))
                        .replace("{countPlus}", String.valueOf(displayCount));
                } else {
                    format = config.getRepeatMergeFormat()
                        .replace("{content}", String.valueOf(c))
                        .replace("{count}", String.valueOf(count));
                }
                result.append(format);
            } else {
                result.append(String.valueOf(c).repeat(count));
            }
            
            i += count;
        }
        
        return result.toString();
    }
}