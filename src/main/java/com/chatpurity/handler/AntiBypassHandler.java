package com.chatpurity.handler;

import com.chatpurity.config.ChatPurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 防绕过处理器
 * 
 * <p>检测和处理玩家尝试绕过过滤的行为：
 * <ul>
 *   <li>颜色代码绕过：使用 § 符号分隔敏感词</li>
 *   <li>Unicode 变体绕过：使用相似字符替代</li>
 *   <li>拼音混合绕过：混合拼音和汉字</li>
 *   <li>谐音字绕过：使用发音相似的字符</li>
 * </ul>
 * 
 * @see ChatPurityConfig 配置管理
 */
public class AntiBypassHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AntiBypassHandler.class);
    private final ChatPurityConfig config;
    
    // 谐音字库缓存：字符 -> 谐音字列表
    private Map<String, List<String>> homophoneLibrary;
    private boolean homophoneLibraryLoaded = false;

    /**
     * 构造防绕过处理器
     * @param config 配置实例
     */
    public AntiBypassHandler(ChatPurityConfig config) {
        this.config = config;
    }
    
    /**
     * 构造防绕过处理器（带自定义库路径）
     * @param config 配置实例
     * @param configDir 配置目录路径
     */
    public AntiBypassHandler(ChatPurityConfig config, Path configDir) {
        this.config = config;
        loadHomophoneLibrary(configDir);
    }
    
    /**
     * 加载谐音字库文件
     * 如果配置目录中不存在，则从 jar 包内复制默认文件
     * @param configDir 配置目录
     */
    public void loadHomophoneLibrary(Path configDir) {
        if (homophoneLibraryLoaded) {
            return;
        }
        
        Path homophoneDir = configDir.resolve("homophone");
        Path homophoneFile = homophoneDir.resolve("HomophoneDictionary.txt");
        
        // 如果文件不存在，从 jar 包内复制默认文件
        if (!Files.exists(homophoneFile)) {
            try {
                // 确保目录存在
                if (!Files.exists(homophoneDir)) {
                    Files.createDirectories(homophoneDir);
                }
                
                // 从 jar 包内复制默认谐音字库
                var inputStream = getClass().getClassLoader().getResourceAsStream("homophone/HomophoneDictionary.txt");
                if (inputStream != null) {
                    try (inputStream) {
                        Files.copy(inputStream, homophoneFile);
                        LOGGER.info("已复制默认谐音字库到: {}", homophoneFile);
                    }
                } else {
                    LOGGER.error("无法从 jar 包内读取默认谐音字库");
                    homophoneLibraryLoaded = true;
                    return;
                }
            } catch (IOException e) {
                LOGGER.error("复制默认谐音字库失败: {}", e.getMessage());
                homophoneLibraryLoaded = true;
                return;
            }
        }
        
        try {
            homophoneLibrary = new HashMap<>();
            List<String> lines = Files.readAllLines(homophoneFile);
            
            int lineCount = 0;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;
                
                // parts[0] 是拼音，parts[1..n] 是谐音字
                List<String> homophones = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    if (!parts[i].isEmpty()) {
                        homophones.add(parts[i]);
                    }
                }
                
                // 为每个谐音字建立反向映射
                for (String homophone : homophones) {
                    // 该字的所有谐音字（包括自己）
                    List<String> allHomophones = homophoneLibrary.computeIfAbsent(homophone, k -> new ArrayList<>());
                    for (String h : homophones) {
                        if (!allHomophones.contains(h)) {
                            allHomophones.add(h);
                        }
                    }
                }
                
                lineCount++;
            }
            
            homophoneLibraryLoaded = true;
            LOGGER.info("成功加载谐音字库: {} 个拼音组，覆盖 {} 个字符", lineCount, homophoneLibrary.size());
            
        } catch (IOException e) {
            LOGGER.error("加载谐音字库失败: {}", e.getMessage(), e);
            homophoneLibraryLoaded = true;
        }
    }
    
    /**
     * 预处理消息，检测绕过尝试
     * @param message 原始消息
     * @return 处理后的消息
     */
    public String preprocessMessage(String message) {
        if (!config.isEnableAntiBypass()) {
            return message;
        }
        
        String result = message;
        
        // 检测颜色代码绕过
        if (config.isDetectColorCodes()) {
            result = detectColorCodeBypass(result);
        }
        
        // 检测 Unicode 变体绕过
        if (config.isDetectUnicodeVariants()) {
            result = detectUnicodeVariantBypass(result);
        }
        
        // 检测拼音混合绕过
        if (config.isDetectPinyinMix()) {
            result = detectPinyinMixBypass(result);
        }
        
        // 检测谐音字绕过
        if (config.isDetectHomophones()) {
            result = detectHomophoneBypass(result);
        }
        
        return result;
    }
    
    // 简化的方法，用于向后兼容
    public boolean containsBypassAttempts(String message) {
        if (!config.isEnableAntiBypass()) {
            return false;
        }
        
        // 检测各种绕过尝试
        return hasColorCodeBypass(message) ||
               hasUnicodeVariantBypass(message) ||
               hasPinyinMixBypass(message) ||
               hasHomophoneBypass(message);
    }
    
    /**
     * 检测是否使用颜色代码绕过
     * 只有当颜色代码被用于分隔敏感词时才判定为绕过尝试
     */
    private boolean hasColorCodeBypass(String message) {
        if (!config.isDetectColorCodes()) {
            return false;
        }
        
        // 如果消息中不包含颜色代码，直接返回 false
        if (!message.contains("§")) {
            return false;
        }
        
        // 移除所有颜色代码后的纯净文本
        String cleanedMessage = message.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        
        // 检查移除颜色代码后是否形成了黑名单词汇
        // 只有当颜色代码被插入到敏感词中间时才判定为绕过
        if (config.isEnableBlacklist()) {
            for (String word : config.getBlacklist()) {
                // 原始消息不包含该词，但移除颜色代码后包含
                boolean originallyContains = config.isIgnoreCase() 
                    ? message.toLowerCase().contains(word.toLowerCase())
                    : message.contains(word);
                boolean cleanedContains = config.isIgnoreCase()
                    ? cleanedMessage.toLowerCase().contains(word.toLowerCase())
                    : cleanedMessage.contains(word);
                
                // 如果移除颜色代码后才出现敏感词，说明是绕过尝试
                if (!originallyContains && cleanedContains) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 检测是否使用 Unicode 变体绕过
     */
    private boolean hasUnicodeVariantBypass(String message) {
        if (!config.isDetectUnicodeVariants()) {
            return false;
        }
        // 使用配置中的 Unicode 变体映射
        Map<String, String> variantMap = config.getUnicodeVariantMap();
        for (String variant : variantMap.keySet()) {
            if (message.contains(variant)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检测是否使用拼音混合绕过
     */
    private boolean hasPinyinMixBypass(String message) {
        if (!config.isDetectPinyinMix()) {
            return false;
        }
        // 使用配置中的拼音检测列表
        List<String> pinyins = config.getPinyinDetectList();
        String lowerMessage = message.toLowerCase();
        for (String pinyin : pinyins) {
            if (lowerMessage.contains(pinyin.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检测是否使用谐音字绕过
     */
    private boolean hasHomophoneBypass(String message) {
        if (!config.isDetectHomophones()) {
            return false;
        }
        // 检查谐音字映射
        Map<String, List<String>> homophoneMap = config.getHomophoneMap();
        for (Map.Entry<String, List<String>> entry : homophoneMap.entrySet()) {
            List<String> homophones = entry.getValue();
            
            // 检查是否使用 ["all"] 特殊值
            if (homophones != null && homophones.size() == 1 && "all".equalsIgnoreCase(homophones.get(0))) {
                homophones = getCommonHomophones(entry.getKey());
            }
            
            if (homophones != null) {
                for (String homophone : homophones) {
                    if (message.contains(homophone)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }    
    /**
     * 检测颜色代码绕过
     * 例如："广§r告"
     */
    private String detectColorCodeBypass(String message) {
        // 移除所有 Minecraft 颜色代码
        return message.replaceAll("§[0-9a-fk-or]", "");
    }
    
    /**
     * 检测 Unicode 变体绕过
     * 例如："广吿"（使用吿代替告）
     */
    private String detectUnicodeVariantBypass(String message) {
        // 使用配置中的 Unicode 变体映射
        Map<String, String> variantMap = config.getUnicodeVariantMap();
        
        String result = message;
        for (Map.Entry<String, String> entry : variantMap.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
    
    /**
     * 检测拼音混合绕过
     * 例如："guang告"、"广gao"
     */
    private String detectPinyinMixBypass(String message) {
        // 使用配置中的拼音到汉字映射
        Map<String, String> pinyinMap = config.getPinyinToCharMap();
        
        String result = message;
        String lowerMessage = message.toLowerCase();
        
        // 检测拼音在汉字前/后
        for (Map.Entry<String, String> entry : pinyinMap.entrySet()) {
            String pinyin = entry.getKey();
            String chinese = entry.getValue();
            
            // 拼音 + 汉字
            result = result.replace(pinyin + chinese, chinese + chinese);
            result = result.replace(pinyin.toUpperCase() + chinese, chinese + chinese);
            result = result.replace(pinyin.substring(0, 1).toUpperCase() + pinyin.substring(1) + chinese, chinese + chinese);
            // 汉字 + 拼音
            result = result.replace(chinese + pinyin, chinese + chinese);
            result = result.replace(chinese + pinyin.toUpperCase(), chinese + chinese);
        }
        
        return result;
    }
    
    /**
     * 检测谐音字绕过
     * 例如："光告"（光是广的谐音）
     * 
     * 支持特殊值 ["all"] 表示自动获取该字的所有常见谐音字
     */
    private String detectHomophoneBypass(String message) {
        Map<String, List<String>> homophoneMap = config.getHomophoneMap();
        
        String result = message;
        
        // 遍历谐音字映射
        for (Map.Entry<String, List<String>> entry : homophoneMap.entrySet()) {
            String original = entry.getKey();
            List<String> homophones = entry.getValue();
            
            // 检查是否使用 ["all"] 特殊值
            if (homophones != null && homophones.size() == 1 && "all".equalsIgnoreCase(homophones.get(0))) {
                // 自动获取该字的所有常见谐音字
                homophones = getCommonHomophones(original);
            }
            
            // 将所有谐音字替换为原字
            if (homophones != null) {
                for (String homophone : homophones) {
                    result = result.replace(homophone, original);
                }
            }
        }
        
        return result;
    }
    
    /**
     * 获取字符的常见谐音字列表
     * 使用外部谐音字库文件
     * @param ch 原字符
     * @return 谐音字列表，如果不存在则返回空列表
     */
    private List<String> getCommonHomophones(String ch) {
        // 使用外部谐音字库
        if (homophoneLibrary != null && homophoneLibrary.containsKey(ch)) {
            return homophoneLibrary.get(ch);
        }
        
        // 未找到则返回空列表
        return new ArrayList<>();
    }
    
    /**
     * 检测消息是否包含绕过尝试
     * @param originalMessage 原始消息
     * @param processedMessage 处理后的消息
     * @return true 表示检测到绕过
     */
    public boolean isBypassAttempt(String originalMessage, String processedMessage) {
        if (!config.isEnableAntiBypass()) {
            return false;
        }
        
        // 如果处理后的消息与原始消息不同，说明检测到绕过
        return !originalMessage.equals(processedMessage);
    }
}