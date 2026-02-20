package com.chatpurity.handler;

import com.chatpurity.config.ChatPurityConfig;
import com.chatpurity.util.ChatPurityUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class ChatHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    private final ChatPurityConfig config;
    
    // 正则表达式缓存，使用 LRU 策略
    private static final int MAX_CACHE_SIZE = 500;
    private final LinkedHashMap<String, Pattern> patternCache;
    private final ReentrantLock cacheLock = new ReentrantLock();

    public ChatHandler(ChatPurityConfig config) {
        this.config = config;
        // 使用 LinkedHashMap 实现 LRU 缓存
        // accessOrder=true 表示按访问顺序排序，最近访问的在最后
        this.patternCache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
    }
    
    public String processMessage(Text originalText, ServerPlayerEntity player, MinecraftServer server) {
        String playerName = player != null ? player.getName().getString() : "unknown";
        
        // 禁言检查始终生效（即使关闭过滤功能）
        if (isMuted(playerName)) {
            if (player != null) {
                String remainingTime = getRemainingMuteTimeFormatted(playerName);
                player.sendMessage(Text.literal("§c[ChatPurity] 你已被禁言，剩余时间: " + remainingTime));
            }
            return null;
        }
        
        // 检查过滤功能总开关，关闭则跳过所有检测
        if (!config.isEnableFilter()) {
            return originalText.getString();
        }
        
        if (config.isEnableAntiSpam() && player != null) {
            if (checkAntiSpam(playerName, player, server)) {
                return null;
            }
        }
        
        String message = originalText.getString();
        
        if (config.isEnableWhitelist() && isWhitelisted(message)) {
            return message;
        }

        String processedMessage = applyReplacements(message, player, server);
        
        if (config.isEnableBlacklist() && isBlacklisted(processedMessage, player, server)) {
            return null;
        }
        
        return processedMessage;
    }
    
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
    
    private String applyReplacements(String message, ServerPlayerEntity player, MinecraftServer server) {
        Map<String, String> replacements = config.getReplacements();
        if (replacements.isEmpty()) {
            return message;
        }
        
        String result = message;
        
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String key = entry.getKey();
            String replacement = entry.getValue();
            String pattern;
            String mode;
            
            // 格式: "原词":"替换词":模式 或 "原词":"替换词"
            // key 可能是 原词 或 原词:模式
            // replacement 是 替换词
            
            // 解析 key 中的词组和模式
            pattern = key;
            mode = "";
            
            // 处理词组模式 "[词1,词2]:模式" 的情况
            if (key.startsWith("[")) {
                int bracketEnd = key.indexOf("]");
                if (bracketEnd != -1) {
                    pattern = key.substring(0, bracketEnd + 1); // 包含 ]
                    if (bracketEnd + 1 < key.length() && key.charAt(bracketEnd + 1) == ':') {
                        mode = key.substring(bracketEnd + 2); // 冒号后的模式
                    }
                }
            } else if (key.contains(":")) {
                // 普通模式 "原词:模式"
                int firstColon = key.indexOf(":");
                pattern = key.substring(0, firstColon);
                mode = key.substring(firstColon + 1);
            }
            
            if (pattern != null && !pattern.isEmpty() && replacement != null) {
                if (mode.isEmpty()) {
                    // 即使模式为空，也需要检查是否是词组模式
                    if (pattern.startsWith("[") && pattern.endsWith("]")) {
                        // 词组模式：替换检测到的词汇组合
                        List<String> requiredWords = parseCombinedWords(pattern);
                        if (containsAllWords(result, requiredWords, false)) {
                            result = replaceCombinedWords(result, requiredWords, replacement, player, server);
                        }
                    } else {
                        result = replaceAll(result, pattern, replacement);
                    }
                } else {
                    result = replaceWithMode(result, pattern, replacement, mode, player, server);
                }
            }
        }
        
        return result;
    }
    
    private String replaceWithMode(String message, String pattern, String replacement, String mode, ServerPlayerEntity player, MinecraftServer server) {
        String[] modes = mode.split(":");
        
        // 首先检查 OP 权限（如果玩家 OP 等级高于限制，跳过替换）
        for (String currentMode : modes) {
            currentMode = currentMode.trim();
            if (currentMode.toLowerCase().startsWith("op")) {
                if (shouldSkipDueToOpPermission(player, server, currentMode)) {
                    // 玩家 OP 等级高于限制，不进行替换
                    return message;
                }
                break; // 只检查第一个 op 模式
            }
        }
        
        // 检查是否是词组模式
        if (pattern.startsWith("[") && pattern.endsWith("]")) {
            boolean hasHomophone = Arrays.stream(modes).anyMatch(m -> m.trim().equals("homophone"));
            boolean hasPinyinAbbr = Arrays.stream(modes).anyMatch(m -> m.trim().equals("pinyin_abbr"));
            boolean hasPinyinFull = Arrays.stream(modes).anyMatch(m -> m.trim().equals("pinyin_full"));
            List<String> requiredWords = parseCombinedWords(pattern);
            
            if (containsAllWords(message, requiredWords, hasHomophone)) {
                return replaceCombinedWords(message, requiredWords, replacement, player, server);
            }
            if (hasPinyinAbbr && checkCombinedPinyinAbbr(message, requiredWords)) {
                return replaceCombinedPinyinAbbr(message, requiredWords, replacement);
            }
            if (hasPinyinFull && checkCombinedPinyinFull(message, requiredWords)) {
                return replaceCombinedPinyinFull(message, requiredWords, replacement);
            }
            return message;
        }
        
        // 非词组模式：检查各个检测模式
        for (String currentMode : modes) {
            currentMode = currentMode.trim();
            
            if (currentMode.startsWith("op")) {
                // OP 已在上面处理，跳过
                continue;
            }
            
            if ("exact_match".equals(currentMode)) {
                if (checkExactMatch(message, pattern)) {
                    return replacement;
                }
            } else if ("homophone".equals(currentMode)) {
                if (checkHomophone(message, pattern)) {
                    return replaceHomophone(message, pattern, replacement);
                }
            } else if ("pinyin_abbr".equals(currentMode)) {
                if (checkPinyinAbbr(message, pattern)) {
                    return replacePinyinAbbr(message, pattern, replacement);
                }
            } else if ("pinyin_full".equals(currentMode)) {
                if (checkPinyinFull(message, pattern)) {
                    return replacePinyinFull(message, pattern, replacement);
                }
            }
        }
        
        // 没有匹配任何检测模式，使用普通替换
        return replaceAll(message, pattern, replacement);
    }
    
    /**
     * 解析词组模式字符串，返回词列表
     * 例如 "[我,喜,欢]" -> ["我", "喜", "欢"]
     */
    private List<String> parseCombinedWords(String pattern) {
        if (!pattern.startsWith("[") || !pattern.endsWith("]")) {
            return Collections.emptyList();
        }
        String innerContent = pattern.substring(1, pattern.length() - 1);
        String[] parts = innerContent.split(",");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            words.add(part.trim());
        }
        return words;
    }
    
    /**
     * 替换词组模式检测到的词汇组合
     * 例如：消息"我喜欢你"，词汇组合["我","喜","欢"]，替换为"我讨厌"
     * 应该找到"我喜欢"并替换为"我讨厌"
     */
    private String replaceCombinedWords(String message, List<String> requiredWords, String replacement, ServerPlayerEntity player, MinecraftServer server) {
        if (requiredWords.isEmpty()) {
            return message;
        }
        
        // 预处理消息和词汇用于搜索（考虑大小写）
        String messageForSearch = config.isIgnoreCase() ? message.toLowerCase() : message;
        List<String> wordsForSearch = config.isIgnoreCase() ? 
            requiredWords.stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toList()) : 
            requiredWords;
        
        // 查找每个词汇在消息中的位置
        List<Integer> wordPositions = new ArrayList<>();
        int searchStartIndex = 0;
        
        for (String word : wordsForSearch) {
            int wordIndex = messageForSearch.indexOf(word, searchStartIndex);
            if (wordIndex == -1) {
                // 词汇未找到，无法进行替换
                return message;
            }
            wordPositions.add(wordIndex);
            searchStartIndex = wordIndex + word.length();
        }
        
        // 检查词汇是否按顺序出现（允许间隔）
        for (int i = 1; i < wordPositions.size(); i++) {
            if (wordPositions.get(i) < wordPositions.get(i - 1)) {
                // 词汇未按顺序出现，无法进行替换
                return message;
            }
        }
        
        // 构建原始词汇组合字符串（包含词汇和它们之间的所有字符）
        StringBuilder originalCombination = new StringBuilder();
        int currentIndex = wordPositions.get(0);
        
        for (int i = 0; i < requiredWords.size(); i++) {
            String word = requiredWords.get(i);
            int wordIndex = wordPositions.get(i);
            
            // 添加词汇前的间隔字符（第一个词除外）
            if (i > 0) {
                int prevWordEnd = wordPositions.get(i - 1) + wordsForSearch.get(i - 1).length();
                if (prevWordEnd < wordIndex) {
                    originalCombination.append(message.substring(prevWordEnd, wordIndex));
                }
            }
            
            // 添加词汇本身
            originalCombination.append(message.substring(wordIndex, wordIndex + word.length()));
        }
        
        // 使用替换词替换原始词汇组合
        String originalStr = originalCombination.toString();
        return replaceAll(message, originalStr, replacement);
    }
    
    /**
     * 替换消息中匹配目标词同音词的子串
     * 例如：目标词"卧槽"，消息"我草你好"，同音词检测后替换为"***"
     * 结果: "***你好"
     */
    private String replaceHomophone(String message, String targetWord, String replacement) {
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 获取目标词的拼音序列
        List<String> targetPinyins = convertToPinyinList(targetWord, homophoneMap);
        if (targetPinyins.isEmpty()) {
            // 如果无法获取拼音，回退到普通替换
            return replaceAll(message, targetWord, replacement);
        }
        
        // 获取消息的拼音序列
        List<String> messagePinyins = convertToPinyinList(message, homophoneMap);
        
        // 在消息中查找匹配目标拼音序列的子串
        // 需要找到消息中哪些字符对应的拼音与目标词匹配
        int targetLen = targetPinyins.size();
        
        for (int i = 0; i <= messagePinyins.size() - targetLen; i++) {
            boolean match = true;
            for (int j = 0; j < targetLen; j++) {
                if (!messagePinyins.get(i + j).equals(targetPinyins.get(j))) {
                    match = false;
                    break;
                }
            }
            
            if (match) {
                // 找到匹配的同音词，需要找到消息中对应的原始字符
                // 计算消息中对应的字符位置
                int[] charPositions = findCharPositionsForPinyinRange(message, homophoneMap, i, targetLen);
                if (charPositions != null) {
                    int startPos = charPositions[0];
                    int endPos = charPositions[1];
                    String matchedText = message.substring(startPos, endPos);
                    return replaceAll(message, matchedText, replacement);
                }
            }
        }
        
        return message;
    }
    
    /**
     * 找到消息中对应指定拼音范围的字符位置
     * @param message 原始消息
     * @param homophoneMap 同音字库
     * @param pinyinStart 拼音序列起始位置
     * @param pinyinLength 拼音序列长度
     * @return [startPos, endPos] 字符位置范围，如果找不到返回null
     */
    private int[] findCharPositionsForPinyinRange(String message, Map<String, String> homophoneMap, int pinyinStart, int pinyinLength) {
        int currentPinyinIndex = 0;
        int startCharPos = -1;
        int endCharPos = 0;
        
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            
            // 跳过英文字母和符号
            if (isEnglish(c) || isSymbol(c)) {
                continue;
            }
            
            String charStr = String.valueOf(c);
            String pinyin = homophoneMap.get(charStr);
            
            if (pinyin != null) {
                if (currentPinyinIndex == pinyinStart) {
                    startCharPos = i;
                }
                if (currentPinyinIndex >= pinyinStart && currentPinyinIndex < pinyinStart + pinyinLength) {
                    endCharPos = i + 1;
                }
                currentPinyinIndex++;
                
                if (currentPinyinIndex >= pinyinStart + pinyinLength) {
                    break;
                }
            } else {
                // 字符不在同音字库中，保留原始字符
                if (currentPinyinIndex == pinyinStart) {
                    startCharPos = i;
                }
                if (currentPinyinIndex >= pinyinStart && currentPinyinIndex < pinyinStart + pinyinLength) {
                    endCharPos = i + 1;
                }
                currentPinyinIndex++;
                
                if (currentPinyinIndex >= pinyinStart + pinyinLength) {
                    break;
                }
            }
        }
        
        if (startCharPos >= 0 && endCharPos > startCharPos) {
            return new int[] { startCharPos, endCharPos };
        }
        return null;
    }
    
    private String replaceAll(String message, String pattern, String replacement) {
        if (config.isIgnoreCase()) {
            // 使用 LRU 缓存的正则表达式进行不区分大小写的替换，提高性能
            try {
                // 从缓存获取或创建 Pattern（线程安全）
                Pattern regex;
                cacheLock.lock();
                try {
                    regex = patternCache.get(pattern);
                    if (regex == null) {
                        regex = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
                        patternCache.put(pattern, regex);
                    }
                } finally {
                    cacheLock.unlock();
                }
                
                Matcher matcher = regex.matcher(message);
                
                if (!matcher.find()) {
                    return message;
                }
                
                // 重置匹配器以进行替换
                matcher.reset();
                return matcher.replaceAll(Matcher.quoteReplacement(replacement));
            } catch (Exception e) {
                // 如果正则表达式编译失败，回退到原始方法
                LOGGER.warn("Failed to compile regex pattern '{}', falling back to manual replacement", pattern, e);
                return manualReplaceAll(message, pattern, replacement, true);
            }
        } else {
            if (!message.contains(pattern)) {
                return message;
            }
            
            return message.replace(pattern, replacement);
        }
    }
    
    /**
     * 清理正则表达式缓存（配置重载时调用）
     */
    public void clearPatternCache() {
        cacheLock.lock();
        try {
            patternCache.clear();
            LOGGER.debug("Pattern cache cleared");
        } finally {
            cacheLock.unlock();
        }
    }
    
    /**
     * 手动替换方法（回退用）
     */
    private String manualReplaceAll(String message, String pattern, String replacement, boolean ignoreCase) {
        if (ignoreCase) {
            String lowerMessage = message.toLowerCase();
            String lowerPattern = pattern.toLowerCase();
            
            if (!lowerMessage.contains(lowerPattern)) {
                return message;
            }
            
            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < message.length()) {
                if (i + pattern.length() <= message.length() &&
                    message.substring(i, i + pattern.length()).toLowerCase().equals(lowerPattern)) {
                    result.append(replacement);
                    i += pattern.length();
                } else {
                    result.append(message.charAt(i));
                    i++;
                }
            }
            return result.toString();
        } else {
            if (!message.contains(pattern)) {
                return message;
            }
            
            return message.replace(pattern, replacement);
        }
    }
    
    private boolean isBlacklisted(String message, ServerPlayerEntity player, MinecraftServer server) {
        Map<String, String> blacklist = config.getBlacklist();
        
        blacklistLoop:
        for (Map.Entry<String, String> entry : blacklist.entrySet()) {
            String key = entry.getKey();
            String mode = entry.getValue();
            
            // 解析 key 中的词组和模式
            String word = key;
            String actualMode = mode != null ? mode : "";
            
            // 处理词组模式 "[词1,词2]:模式" 的情况
            if (key.startsWith("[")) {
                int bracketEnd = key.indexOf("]");
                if (bracketEnd != -1) {
                    word = key.substring(0, bracketEnd + 1); // 包含 ]
                    if (bracketEnd + 1 < key.length() && key.charAt(bracketEnd + 1) == ':') {
                        // key 中包含模式，需要与 value 中的模式合并
                        String keyMode = key.substring(bracketEnd + 2);
                        if (!actualMode.isEmpty()) {
                            actualMode = keyMode + ":" + actualMode;
                        } else {
                            actualMode = keyMode;
                        }
                    }
                }
            }
            
            if (actualMode.isEmpty()) {
                // 模式为空：检查是否是词组模式
                if (word.startsWith("[") && word.endsWith("]")) {
                    if (containsAllWords(message, parseCombinedWords(word), false)) {
                        if (config.isDebugMode()) {
                            LOGGER.debug("Message blocked by combined words check (no mode): {} -> {}", message, word);
                        }
                        return true;
                    }
                } else if (checkContains(message, word)) {
                    if (config.isDebugMode()) {
                        LOGGER.debug("Message blocked by default contains check: {} -> {}", message, word);
                    }
                    return true;
                }
                continue;
            }
            
            // 解析模式
            String[] modes = actualMode.split(":");
            
            // 首先检查 OP 权限（如果玩家有足够权限，跳过这条黑名单规则）
            for (String currentMode : modes) {
                currentMode = currentMode.trim();
                if (currentMode.toLowerCase().startsWith("op")) {
                    if (shouldSkipDueToOpPermission(player, server, currentMode)) {
                        // 玩家 OP 等级高于限制，跳过这条规则
                        if (config.isDebugMode()) {
                            LOGGER.debug("Skipping blacklist rule due to OP permission: {} -> {}", message, word);
                        }
                        continue blacklistLoop;
                    }
                    break; // 只检查第一个 op 模式
                }
            }
            
            // 检查是否是词组模式
            if (word.startsWith("[") && word.endsWith("]")) {
                boolean hasHomophone = Arrays.stream(modes).anyMatch(m -> m.trim().equals("homophone"));
                boolean hasPinyinAbbr = Arrays.stream(modes).anyMatch(m -> m.trim().equals("pinyin_abbr"));
                boolean hasPinyinFull = Arrays.stream(modes).anyMatch(m -> m.trim().equals("pinyin_full"));
                
                if (containsAllWords(message, parseCombinedWords(word), hasHomophone)) {
                    if (config.isDebugMode()) {
                        LOGGER.debug("Message blocked by combined words check: {} -> {}", message, word);
                    }
                    return true;
                }
                if (hasPinyinAbbr && checkCombinedPinyinAbbr(message, parseCombinedWords(word))) {
                    if (config.isDebugMode()) {
                        LOGGER.debug("Message blocked by combined pinyin_abbr check: {} -> {}", message, word);
                    }
                    return true;
                }
                if (hasPinyinFull && checkCombinedPinyinFull(message, parseCombinedWords(word))) {
                    if (config.isDebugMode()) {
                        LOGGER.debug("Message blocked by combined pinyin_full check: {} -> {}", message, word);
                    }
                    return true;
                }
            } else {
                // 非词组模式：检查各个检测模式
                for (String currentMode : modes) {
                    currentMode = currentMode.trim();
                    
                    if (currentMode.startsWith("op")) {
                        // OP 已在上面处理，跳过
                        continue;
                    }
                    
                    if ("homophone".equals(currentMode)) {
                        if (checkHomophone(message, word)) {
                            if (config.isDebugMode()) {
                                LOGGER.debug("Message blocked by homophone check: {} -> {}", message, word);
                            }
                            return true;
                        }
                    } else if ("exact_match".equals(currentMode)) {
                        if (checkExactMatch(message, word)) {
                            if (config.isDebugMode()) {
                                LOGGER.debug("Message blocked by exact match check: {} -> {}", message, word);
                            }
                            return true;
                        }
                    } else if ("pinyin_abbr".equals(currentMode)) {
                        if (checkPinyinAbbr(message, word)) {
                            if (config.isDebugMode()) {
                                LOGGER.debug("Message blocked by pinyin_abbr check: {} -> {}", message, word);
                            }
                            return true;
                        }
                    } else if ("pinyin_full".equals(currentMode)) {
                        if (checkPinyinFull(message, word)) {
                            if (config.isDebugMode()) {
                                LOGGER.debug("Message blocked by pinyin_full check: {} -> {}", message, word);
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    private boolean checkContains(String message, String targetWord) {
        if (config.isIgnoreCase()) {
            return message.toLowerCase().contains(targetWord.toLowerCase());
        } else {
            return message.contains(targetWord);
        }
    }
    
    /**
     * 检查玩家是否因为 OP 等级高于要求而跳过检测
     * 
     * 逻辑说明：
     * - op1: 只有 OP1 会被屏蔽，OP2/3/4 可以发送
     * - op2: OP1、OP2 会被屏蔽，OP3/4 可以发送
     * - op3: OP1、OP2、OP3 会被屏蔽，OP4 可以发送
     * - op4: 所有人都会被屏蔽
     * 
     * @return true 表示玩家 OP 等级 > requiredLevel，可以跳过检测
     */
    private boolean shouldSkipDueToOpPermission(ServerPlayerEntity player, MinecraftServer server, String opMode) {
        if (player == null || server == null || opMode == null) {
            return false;
        }
        
        int maxBlockedLevel = 4;
        try {
            // opMode 格式: "op1", "op2", "op3", "op4"
            if (opMode.length() >= 2 && opMode.toLowerCase().startsWith("op")) {
                String levelStr = opMode.substring(2);
                if (!levelStr.isEmpty()) {
                    maxBlockedLevel = Integer.parseInt(levelStr);
                }
            }
            // 确保权限等级在有效范围内（1-4）
            if (maxBlockedLevel < 1) maxBlockedLevel = 1;
            if (maxBlockedLevel > 4) maxBlockedLevel = 4;
        } catch (NumberFormatException e) {
            maxBlockedLevel = 4;
        }
        
        // 获取玩家当前的 OP 等级
        int playerLevel = getPlayerOpLevel(player);
        
        // 只有玩家的 OP 等级 > maxBlockedLevel 时，才能跳过检测
        return playerLevel > maxBlockedLevel;
    }
    
    /**
     * 获取玩家的 OP 等级（1-4）
     * 非OP玩家返回 0
     */
    private int getPlayerOpLevel(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }
        
        // 检查各个权限等级
        for (int level = 4; level >= 1; level--) {
            if (player.getCommandSource().getPermissions().hasPermission(
                new Permission.Level(PermissionLevel.fromLevel(level))
            )) {
                return level;
            }
        }
        
        return 0; // 非 OP 玩家
    }
    

    
    private boolean containsAllWords(String message, List<String> requiredWords, boolean checkHomophone) {
        Map<String, String> homophoneMap = config.getHomophoneMap();
        boolean ignoreCase = config.isIgnoreCase();
        
        // 预处理消息用于非同音词检测
        String messageForContains = ignoreCase ? message.toLowerCase() : message;
        
        List<String> messagePinyins = new ArrayList<>();
        if (checkHomophone) {
            messagePinyins = convertToPinyinList(message, homophoneMap);
        }
        
        for (String requiredWord : requiredWords) {
            boolean found = false;
            
            if (checkHomophone) {
                List<String> targetPinyins = convertToPinyinList(requiredWord, homophoneMap);
                
                if (targetPinyins.isEmpty()) {
                    // 没有拼音时，使用普通匹配
                    String wordForCheck = ignoreCase ? requiredWord.toLowerCase() : requiredWord;
                    if (messageForContains.contains(wordForCheck)) {
                        found = true;
                    }
                } else {
                    // 使用顺序检查，确保拼音连续出现
                    if (containsPinyinSequence(messagePinyins, targetPinyins)) {
                        found = true;
                    }
                }
            } else {
                // 非同音词模式，使用普通匹配
                String wordForCheck = ignoreCase ? requiredWord.toLowerCase() : requiredWord;
                if (messageForContains.contains(wordForCheck)) {
                    found = true;
                }
            }
            
            if (!found) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean checkExactMatch(String message, String targetWord) {
        if (message.equals(targetWord)) {
            return true;
        }
        
        if (message.trim().equals(targetWord)) {
            return true;
        }
        
        if (isRepeatedWord(message, targetWord)) {
            return true;
        }
        
        return false;
    }
    
    private boolean isRepeatedWord(String message, String targetWord) {
        if (message.isEmpty() || targetWord.isEmpty()) {
            return false;
        }
        
        int wordLen = targetWord.length();
        if (message.length() % wordLen != 0) {
            return false;
        }
        
        int repeatCount = message.length() / wordLen;
        if (repeatCount < 2) {
            return false;
        }
        
        for (int i = 0; i < repeatCount; i++) {
            String segment = message.substring(i * wordLen, (i + 1) * wordLen);
            if (!segment.equals(targetWord)) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean checkHomophone(String message, String targetWord) {
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        List<String> targetPinyins = convertToPinyinList(targetWord, homophoneMap);
        
        if (targetPinyins.isEmpty()) {
            return false;
        }
        
        List<String> messagePinyins = convertToPinyinList(message, homophoneMap);
        
        return containsPinyinSequence(messagePinyins, targetPinyins);
    }
    
    /**
     * 检查消息中是否包含目标词的拼音缩写
     * 例如：目标词"傻逼"，拼音缩写为"sb"，消息中输入"sb"会被检测到
     * 
     * @param message 消息内容
     * @param targetWord 目标词（中文）
     * @return 是否匹配
     */
    private boolean checkPinyinAbbr(String message, String targetWord) {
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 获取目标词的拼音列表
        List<String> targetPinyins = convertToPinyinList(targetWord, homophoneMap);
        if (targetPinyins.isEmpty()) {
            return false;
        }
        
        // 构建拼音缩写（取每个拼音的首字母）
        StringBuilder abbrBuilder = new StringBuilder();
        for (String pinyin : targetPinyins) {
            if (!pinyin.isEmpty()) {
                abbrBuilder.append(Character.toLowerCase(pinyin.charAt(0)));
            }
        }
        String targetAbbr = abbrBuilder.toString();
        
        if (targetAbbr.isEmpty()) {
            return false;
        }
        
        // 检查消息中是否包含拼音缩写
        String messageLower = config.isIgnoreCase() ? message.toLowerCase() : message;
        String abbrLower = targetAbbr.toLowerCase();
        
        return messageLower.contains(abbrLower);
    }
    
    /**
     * 检查消息中是否包含目标词的完整拼音
     * 例如：目标词"傻逼"，拼音为"shabi"，消息中输入"shabi"会被检测到
     * 
     * @param message 消息内容
     * @param targetWord 目标词（中文）
     * @return 是否匹配
     */
    private boolean checkPinyinFull(String message, String targetWord) {
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 获取目标词的拼音列表
        List<String> targetPinyins = convertToPinyinList(targetWord, homophoneMap);
        if (targetPinyins.isEmpty()) {
            return false;
        }
        
        // 构建完整拼音（拼接所有拼音）
        StringBuilder pinyinBuilder = new StringBuilder();
        for (String pinyin : targetPinyins) {
            pinyinBuilder.append(pinyin.toLowerCase());
        }
        String targetPinyin = pinyinBuilder.toString();
        
        if (targetPinyin.isEmpty()) {
            return false;
        }
        
        // 检查消息中是否包含完整拼音
        String messageLower = config.isIgnoreCase() ? message.toLowerCase() : message;
        
        return messageLower.contains(targetPinyin);
    }
    
    /**
     * 替换消息中匹配目标词完整拼音的子串
     * 例如：目标词"傻逼"，拼音为"shabi"，消息"你个shabi啊"会被替换为"你个***啊"
     * 
     * @param message 原始消息
     * @param targetWord 目标词（中文）
     * @param replacement 替换内容
     * @return 替换后的消息
     */
    private String replacePinyinFull(String message, String targetWord, String replacement) {
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 获取目标词的拼音列表
        List<String> targetPinyins = convertToPinyinList(targetWord, homophoneMap);
        if (targetPinyins.isEmpty()) {
            return message;
        }
        
        // 构建完整拼音
        StringBuilder pinyinBuilder = new StringBuilder();
        for (String pinyin : targetPinyins) {
            pinyinBuilder.append(pinyin.toLowerCase());
        }
        String targetPinyin = pinyinBuilder.toString();
        
        if (targetPinyin.isEmpty()) {
            return message;
        }
        
        // 替换消息中的完整拼音
        return replaceAll(message, targetPinyin, replacement);
    }
    
    /**
     * 替换消息中匹配目标词拼音缩写的子串
     * 例如：目标词"傻逼"，拼音缩写为"sb"，消息"你个sb啊"会被替换为"你个***啊"
     * 
     * @param message 原始消息
     * @param targetWord 目标词（中文）
     * @param replacement 替换内容
     * @return 替换后的消息
     */
    private String replacePinyinAbbr(String message, String targetWord, String replacement) {
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 获取目标词的拼音列表
        List<String> targetPinyins = convertToPinyinList(targetWord, homophoneMap);
        if (targetPinyins.isEmpty()) {
            return message;
        }
        
        // 构建拼音缩写
        StringBuilder abbrBuilder = new StringBuilder();
        for (String pinyin : targetPinyins) {
            if (!pinyin.isEmpty()) {
                abbrBuilder.append(Character.toLowerCase(pinyin.charAt(0)));
            }
        }
        String targetAbbr = abbrBuilder.toString();
        
        if (targetAbbr.isEmpty()) {
            return message;
        }
        
        // 替换消息中的拼音缩写
        return replaceAll(message, targetAbbr, replacement);
    }
    
    /**
     * 检查消息中是否包含词组的拼音缩写
     * 例如：词组["我","喜","欢"]，拼音缩写为"wxh"，消息中输入"wxh"会被检测到
     * 
     * @param message 消息内容
     * @param requiredWords 词组列表
     * @return 是否匹配
     */
    private boolean checkCombinedPinyinAbbr(String message, List<String> requiredWords) {
        if (requiredWords.isEmpty()) {
            return false;
        }
        
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 构建词组的拼音缩写
        StringBuilder abbrBuilder = new StringBuilder();
        for (String word : requiredWords) {
            List<String> pinyins = convertToPinyinList(word, homophoneMap);
            if (!pinyins.isEmpty()) {
                // 取第一个字的拼音首字母
                abbrBuilder.append(Character.toLowerCase(pinyins.get(0).charAt(0)));
            }
        }
        String targetAbbr = abbrBuilder.toString();
        
        if (targetAbbr.isEmpty()) {
            return false;
        }
        
        // 检查消息中是否包含拼音缩写
        String messageLower = config.isIgnoreCase() ? message.toLowerCase() : message;
        String abbrLower = targetAbbr.toLowerCase();
        
        return messageLower.contains(abbrLower);
    }
    
    /**
     * 替换消息中匹配词组拼音缩写的子串
     * 
     * @param message 原始消息
     * @param requiredWords 词组列表
     * @param replacement 替换内容
     * @return 替换后的消息
     */
    private String replaceCombinedPinyinAbbr(String message, List<String> requiredWords, String replacement) {
        if (requiredWords.isEmpty()) {
            return message;
        }
        
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 构建词组的拼音缩写
        StringBuilder abbrBuilder = new StringBuilder();
        for (String word : requiredWords) {
            List<String> pinyins = convertToPinyinList(word, homophoneMap);
            if (!pinyins.isEmpty()) {
                abbrBuilder.append(Character.toLowerCase(pinyins.get(0).charAt(0)));
            }
        }
        String targetAbbr = abbrBuilder.toString();
        
        if (targetAbbr.isEmpty()) {
            return message;
        }
        
        return replaceAll(message, targetAbbr, replacement);
    }
    
    /**
     * 检查消息中是否包含词组的完整拼音
     * 例如：词组["我","喜","欢"]，完整拼音为"woxihuan"，消息中输入"woxihuan"会被检测到
     * 
     * @param message 消息内容
     * @param requiredWords 词组列表
     * @return 是否匹配
     */
    private boolean checkCombinedPinyinFull(String message, List<String> requiredWords) {
        if (requiredWords.isEmpty()) {
            return false;
        }
        
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 构建词组的完整拼音
        StringBuilder pinyinBuilder = new StringBuilder();
        for (String word : requiredWords) {
            List<String> pinyins = convertToPinyinList(word, homophoneMap);
            for (String pinyin : pinyins) {
                pinyinBuilder.append(pinyin.toLowerCase());
            }
        }
        String targetPinyin = pinyinBuilder.toString();
        
        if (targetPinyin.isEmpty()) {
            return false;
        }
        
        // 检查消息中是否包含完整拼音
        String messageLower = config.isIgnoreCase() ? message.toLowerCase() : message;
        
        return messageLower.contains(targetPinyin);
    }
    
    /**
     * 替换消息中匹配词组完整拼音的子串
     * 
     * @param message 原始消息
     * @param requiredWords 词组列表
     * @param replacement 替换内容
     * @return 替换后的消息
     */
    private String replaceCombinedPinyinFull(String message, List<String> requiredWords, String replacement) {
        if (requiredWords.isEmpty()) {
            return message;
        }
        
        Map<String, String> homophoneMap = config.getHomophoneMap();
        
        // 构建词组的完整拼音
        StringBuilder pinyinBuilder = new StringBuilder();
        for (String word : requiredWords) {
            List<String> pinyins = convertToPinyinList(word, homophoneMap);
            for (String pinyin : pinyins) {
                pinyinBuilder.append(pinyin.toLowerCase());
            }
        }
        String targetPinyin = pinyinBuilder.toString();
        
        if (targetPinyin.isEmpty()) {
            return message;
        }
        
        return replaceAll(message, targetPinyin, replacement);
    }
    
    private List<String> convertToPinyinList(String text, Map<String, String> homophoneMap) {
        List<String> pinyinList = new ArrayList<>();
        
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            
            if (isEnglish(c) || isSymbol(c)) {
                continue;
            }
            
            String charStr = String.valueOf(c);
            String pinyin = homophoneMap.get(charStr);
            
            if (pinyin != null) {
                pinyinList.add(pinyin);
            } else {
                // 如果字符不在同音字库中，保留原始字符进行比较
                // 这样可以确保未收录的字符不会导致检测失效
                pinyinList.add(charStr);
            }
        }
        
        return pinyinList;
    }
    
    private boolean isEnglish(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
    
    private boolean isSymbol(char c) {
        return !isChinese(c) && !isEnglish(c) && !Character.isDigit(c);
    }
    
    private boolean isChinese(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN;
    }
    
    private boolean containsPinyinSequence(List<String> messagePinyins, List<String> targetPinyins) {
        if (targetPinyins.isEmpty() || messagePinyins.isEmpty()) {
            return false;
        }
        
        for (int i = 0; i <= messagePinyins.size() - targetPinyins.size(); i++) {
            boolean match = true;
            for (int j = 0; j < targetPinyins.size(); j++) {
                if (!messagePinyins.get(i + j).equals(targetPinyins.get(j))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isMuted(String playerName) {
        Map<String, Long> mutedPlayers = config.getMutedPlayers();
        // 直接使用 get 避免 containsKey + get 的竞态条件
        Long muteEndTime = mutedPlayers.get(playerName);
        if (muteEndTime == null) {
            return false;
        }
        
        // 检查是否永久禁言
        if (muteEndTime == Long.MAX_VALUE) {
            return true;
        }
        
        long currentTime = System.currentTimeMillis();
        
        if (currentTime >= muteEndTime) {
            mutedPlayers.remove(playerName);
            try {
                config.save();
            } catch (Exception e) {
                LOGGER.error("Failed to save config when removing muted player: {}", playerName, e);
            }
            return false;
        }
        
        return true;
    }
    
    private String getRemainingMuteTimeFormatted(String playerName) {
        Map<String, Long> mutedPlayers = config.getMutedPlayers();
        // 直接使用 get 避免 containsKey + get 的竞态条件
        Long muteEndTime = mutedPlayers.get(playerName);
        if (muteEndTime == null) {
            return "0秒";
        }
        
        // 检查是否永久禁言
        if (muteEndTime == Long.MAX_VALUE) {
            return "永久";
        }
        
        long currentTime = System.currentTimeMillis();
        long remainingTime = (muteEndTime - currentTime) / 1000;
        
        return ChatPurityUtils.formatDuration(Math.max(0, remainingTime));
    }
    
    private boolean checkAntiSpam(String playerName, ServerPlayerEntity player, MinecraftServer server) {
        Map<String, Integer> messageCount = config.getPlayerMessageCount();
        Map<String, Long> messageTimestamps = config.getPlayerMessageTimestamps();
        
        long currentTime = System.currentTimeMillis();
        long timeWindowMs = config.getAntiSpamTimeWindow() * 1000L;
        int maxMessages = config.getAntiSpamMaxMessages();
        
        // 使用同步块确保所有计数器操作的原子性
        int currentCount;
        boolean shouldTrigger;
        
        synchronized (messageCount) {
            // 获取玩家上次消息时间，如果超过时间窗口则重置计数
            Long lastMessageTime = messageTimestamps.get(playerName);
            currentCount = messageCount.getOrDefault(playerName, 0);
            
            if (lastMessageTime != null && (currentTime - lastMessageTime) > timeWindowMs) {
                currentCount = 0;
            }
            
            // 检查是否即将超过阈值
            // 注意：这里检查的是+1后是否会达到或超过阈值
            shouldTrigger = (currentCount + 1 >= maxMessages);
            
            if (shouldTrigger) {
                // 触发处罚前先清除当前玩家的计数器数据，避免重复触发
                messageCount.remove(playerName);
                messageTimestamps.remove(playerName);
            } else {
                // 未触发处罚，更新计数和时间戳
                messageCount.put(playerName, currentCount + 1);
                messageTimestamps.put(playerName, currentTime);
            }
        }
        
        // 处罚操作在同步块外执行，避免长时间持有锁
        if (shouldTrigger) {
            
            List<Map<String, String>> rules = config.getAntiSpamRules();
            
            if (rules.isEmpty()) {
                String action = config.getAntiSpamAction();
                String actionTime = config.getAntiSpamActionTime();
                
                if ("mute".equals(action)) {
                    long muteTime = ChatPurityUtils.parseTime(actionTime);
                    if (muteTime > 0) {
                        config.getMutedPlayers().put(playerName, ChatPurityUtils.calculateMuteEndTime(muteTime));
                        try {
                            config.save();
                        } catch (Exception e) {
                            LOGGER.error("Failed to save config when muting player for spamming: {}", playerName, e);
                        }
                        player.sendMessage(Text.literal("§c[ChatPurity] 你因刷屏被禁言 " + actionTime));
                        LOGGER.info("Player {} was muted for spamming", playerName);
                    } else {
                        config.getMutedPlayers().put(playerName, Long.MAX_VALUE);
                        try {
                            config.save();
                        } catch (Exception e) {
                            LOGGER.error("Failed to save config when permanently muting player for spamming: {}", playerName, e);
                        }
                        player.sendMessage(Text.literal("§c[ChatPurity] 你因刷屏被永久禁言"));
                        LOGGER.info("Player {} was permanently muted for spamming", playerName);
                    }
                } else if ("kick".equals(action)) {
                    player.networkHandler.disconnect(Text.literal("§c[ChatPurity] 你因刷屏被踢出服务器"));
                    LOGGER.info("Player {} was kicked for spamming", playerName);
                } else if ("ban".equals(action)) {
                    player.networkHandler.disconnect(Text.literal("§c[ChatPurity] 你因刷屏被封禁"));
                    LOGGER.info("Player {} was banned for spamming", playerName);
                    return true;
                }
                
                return true;
            }
            
            for (Map<String, String> rule : rules) {
                String mode = rule.get("mode");
                String time = rule.get("time");
                String message = rule.getOrDefault("message", "你因刷屏被限制");
                
                if ("mute".equals(mode)) {
                    long muteTime = ChatPurityUtils.parseTime(time);
                    if (muteTime > 0) {
                        config.getMutedPlayers().put(playerName, ChatPurityUtils.calculateMuteEndTime(muteTime));
                        try {
                            config.save();
                        } catch (Exception e) {
                            LOGGER.error("Failed to save config when muting player with advanced rule: {}", playerName, e);
                        }
                        player.sendMessage(Text.literal("§c[ChatPurity] " + message));
                        LOGGER.info("Player {} was muted for spamming", playerName);
                    } else {
                        config.getMutedPlayers().put(playerName, Long.MAX_VALUE);
                        try {
                            config.save();
                        } catch (Exception e) {
                            LOGGER.error("Failed to save config when permanently muting player with advanced rule: {}", playerName, e);
                        }
                        player.sendMessage(Text.literal("§c[ChatPurity] " + message));
                        LOGGER.info("Player {} was permanently muted for spamming", playerName);
                    }
                    return true;
                } else if ("kick".equals(mode)) {
                    player.networkHandler.disconnect(Text.literal("§c[ChatPurity] " + message));
                    LOGGER.info("Player {} was kicked for spamming", playerName);
                    return true;
                } else if ("ban".equals(mode)) {
                    player.networkHandler.disconnect(Text.literal("§c[ChatPurity] " + message));
                    LOGGER.info("Player {} was banned for spamming", playerName);
                    return true;
                } else if ("block".equals(mode)) {
                    player.sendMessage(Text.literal("§c[ChatPurity] " + message));
                    LOGGER.info("Player {} message was blocked for spamming", playerName);
                    return true;
                }
            }
        }
        
        // 未触发处罚的情况已在同步块内更新计数器
        return false;
    }
}