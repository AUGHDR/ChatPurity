package com.chatpurity.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ChatPurityConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    
    private Path configPath;
    
    // 异步保存相关 - 使用 AtomicReference 以便在 shutdown 后可重新创建
    private final AtomicReference<ExecutorService> saveExecutorRef = new AtomicReference<>();
    private final AtomicBoolean savePending = new AtomicBoolean(false);
    private static final long SAVE_DEBOUNCE_MS = 1000; // 防抖延迟1秒
    
    // 获取或创建 ExecutorService
    private ExecutorService getSaveExecutor() {
        ExecutorService executor = saveExecutorRef.get();
        if (executor == null || executor.isShutdown()) {
            ExecutorService newExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ChatPurity-SaveThread");
                t.setDaemon(true);
                return t;
            });
            if (saveExecutorRef.compareAndSet(executor, newExecutor)) {
                return newExecutor;
            }
            // 其他线程已经创建了新的 executor
            newExecutor.shutdown();
            return saveExecutorRef.get();
        }
        return executor;
    }
    
    private volatile boolean enableFilter = true;
    private volatile boolean enableWhitelist = true;
    private volatile boolean enableBlacklist = true;
    private volatile boolean ignoreCase = true;
    private volatile boolean notifyBlocked = true;
    private volatile boolean debugMode = false;
    private volatile String blockedMessage = "[ChatPurity] 你的消息已被屏蔽";
    
    private final List<String> whitelist = Collections.synchronizedList(new ArrayList<>());
    
    private final Map<String, String> blacklist = new ConcurrentHashMap<>();
    
    private final Map<String, String> homophoneMap = new ConcurrentHashMap<>();
    
    private final Map<String, String> replacements = new ConcurrentHashMap<>();
    
    private final Map<String, Long> mutedPlayers = new ConcurrentHashMap<>();
    private final Map<String, Integer> playerMessageCount = new ConcurrentHashMap<>();
    private final Map<String, Long> playerMessageTimestamps = new ConcurrentHashMap<>();
    private volatile boolean enableAntiSpam = false;
    private volatile int antiSpamMaxMessages = 5;
    private volatile int antiSpamTimeWindow = 5;
    private volatile String antiSpamAction = "mute";
    private volatile String antiSpamActionTime = "10min";
    private final List<Map<String, String>> antiSpamRules = Collections.synchronizedList(new ArrayList<>());

    public ChatPurityConfig(Path configPath) {
        // configPath 已经是 config/chatpurity/chatpurity.yml
        this.configPath = configPath;
        LOGGER.info("Initializing ChatPurityConfig with path: {}", this.configPath);
        load();
    }
    
    @SuppressWarnings("unchecked")
    public void load() {
        try {
            LOGGER.info("Loading config from: {}", configPath);
            
            // 清空集合，避免reload时数据累积
            whitelist.clear();
            blacklist.clear();
            replacements.clear();
            mutedPlayers.clear();
            antiSpamRules.clear();
            playerMessageCount.clear();
            playerMessageTimestamps.clear();
            
            if (configPath != null && Files.exists(configPath)) {
                String content = Files.readString(configPath);
                Yaml yaml = new Yaml();
                Map<String, Object> data = yaml.loadAs(content, Map.class);
                
                if (data != null) {
                    // 支持两种键名格式: enableFilter 或 enable_filter
                    enableFilter = getBoolean(data, "enable_filter", getBoolean(data, "enableFilter", true));
                    enableWhitelist = getBoolean(data, "enable_whitelist", getBoolean(data, "enableWhitelist", true));
                    enableBlacklist = getBoolean(data, "enable_blacklist", getBoolean(data, "enableBlacklist", true));
                    ignoreCase = getBoolean(data, "ignore_case", getBoolean(data, "ignoreCase", true));
                    notifyBlocked = getBoolean(data, "notify_blocked", getBoolean(data, "notifyBlocked", true));
                    debugMode = getBoolean(data, "debug_mode", getBoolean(data, "debugMode", false));
                    blockedMessage = getString(data, "blocked_message", getString(data, "blockedMessage", "[ChatPurity] 你的消息已被屏蔽"));
                    
                    whitelist.addAll(getStringList(data, "whitelist"));
                    
                    Object blacklistObj = data.get("blacklist");
                    if (blacklistObj instanceof Map) {
                        blacklist.putAll((Map<String, String>) blacklistObj);
                    }
                    
                    Object replacementsObj = data.get("replacements");
                    if (replacementsObj instanceof Map) {
                        replacements.putAll((Map<String, String>) replacementsObj);
                    }
                    
                    Object mutedPlayersObj = data.get("muted_players");
                    if (mutedPlayersObj instanceof Map) {
                        mutedPlayers.putAll((Map<String, Long>) mutedPlayersObj);
                    }
                    
                    enableAntiSpam = getBoolean(data, "enable_anti_spam", false);
                    antiSpamMaxMessages = getInt(data, "anti_spam_max_messages", 5);
                    antiSpamTimeWindow = getInt(data, "anti_spam_time_window", 5);
                    antiSpamAction = getString(data, "anti_spam_action", "mute");
                    antiSpamActionTime = getString(data, "anti_spam_action_time", "10min");
                    
                    Object rulesObj = data.get("anti_spam_rules");
                    if (rulesObj instanceof List) {
                        for (Object ruleObj : (List<?>) rulesObj) {
                            if (ruleObj instanceof Map) {
                                Map<String, String> rule = new HashMap<>();
                                Map<?, ?> ruleMap = (Map<?, ?>) ruleObj;
                                for (Map.Entry<?, ?> entry : ruleMap.entrySet()) {
                                    if (entry.getKey() instanceof String && entry.getValue() != null) {
                                        rule.put((String) entry.getKey(), entry.getValue().toString());
                                    }
                                }
                                antiSpamRules.add(rule);
                            }
                        }
                    }
                }
                LOGGER.info("Config loaded successfully from existing file");
            } else {
                LOGGER.warn("Config file does not exist, creating default config");
                createDefaultConfig();
            }
            
            // 验证配置值的有效性
            validateConfig();
            
            loadHomophoneMap();
        } catch (IOException e) {
            LOGGER.error("Failed to load config", e);
            LOGGER.warn("Creating default config due to error");
            createDefaultConfig();
        }
    }
    
    public void reload() {
        load();
        LOGGER.info("Config reloaded successfully!");
    }
    
    /**
     * 异步保存配置（带防抖）
     * 多次快速调用只会触发一次实际保存
     */
    public void save() {
        if (savePending.compareAndSet(false, true)) {
            getSaveExecutor().submit(() -> {
                try {
                    // 防抖延迟
                    Thread.sleep(SAVE_DEBOUNCE_MS);
                    doSave();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    savePending.set(false);
                }
            });
        }
    }
    
    /**
     * 同步保存配置（立即保存，用于服务器关闭等场景）
     */
    public void saveSync() {
        // 关闭异步保存线程池，确保所有待保存任务完成
        ExecutorService executor = saveExecutorRef.get();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        doSave();
    }
    
    /**
     * 实际执行保存操作
     */
    private void doSave() {
        try {
            if (configPath != null) {
                Path parentDir = configPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }
                
                // 使用完整的配置写入，保留所有注释和格式
                StringBuilder content = new StringBuilder();
                content.append("# ChatPurity 聊天过滤配置\n");
                content.append("# 修改后执行 /chatpurity reload 重载\n\n");
                
                // 基础设置
                content.append("# ────────────────────────────────────\n");
                content.append("# 基础设置\n");
                content.append("# ────────────────────────────────────\n");
                content.append("enable_filter: ").append(enableFilter).append("          # 总开关,关闭后所有过滤功能失效\n");
                content.append("enable_whitelist: ").append(enableWhitelist).append("       # 白名单开关\n");
                content.append("enable_blacklist: ").append(enableBlacklist).append("       # 黑名单开关\n");
                content.append("ignore_case: ").append(ignoreCase).append("            # 忽略大小写(对英文生效)\n");
                content.append("notify_blocked: ").append(notifyBlocked).append("         # 消息被屏蔽时是否提示玩家\n");
                content.append("blocked_message: \"").append(blockedMessage).append("\"\n");
                content.append("debug_mode: ").append(debugMode).append("            # 调试模式,在控制台输出详细日志\n\n");
                
                // 白名单
                content.append("# ────────────────────────────────────\n");
                content.append("# 白名单\n");
                content.append("# ────────────────────────────────────\n");
                content.append("# 包含白名单词汇的消息会跳过黑名单和替换词检测\n");
                content.append("whitelist:\n");
                for (String item : whitelist) {
                    content.append("  - \"").append(escapeYamlString(item)).append("\"\n");
                }
                content.append("# 示例:\n");
                content.append("#   - \"游戏\"\n");
                content.append("#   - \"Minecraft\"\n");
                content.append("\n");
                
                // 黑名单
                content.append("# ────────────────────────────────────\n");
                content.append("# 黑名单\n");
                content.append("# ────────────────────────────────────\n");
                content.append("# 包含黑名单词汇的消息会被屏蔽\n");
                content.append("blacklist:\n");
                for (Map.Entry<String, String> entry : blacklist.entrySet()) {
                    content.append("  \"").append(escapeYamlString(entry.getKey())).append("\": \"").append(escapeYamlString(entry.getValue())).append("\"\n");
                }
                content.append("# 格式: \"词\": \"模式\"\n");
                content.append("# 模式: (空)=包含 | homophone=同音词 | pinyin_abbr=拼音缩写 | pinyin_full=完整拼音 | exact_match=精确 | op1-4=OP权限\n");
                content.append("# OP权限: op1=OP1及以下屏蔽, op2=OP2及以下屏蔽...\n");
                content.append("# 示例:\n");
                content.append("#   \"广告\": \"\"                         # 包含\"广告\"就屏蔽\n");
                content.append("#   \"傻逼\": \"homophone\"                # 同音词检测\n");
                content.append("#   \"傻逼\": \"pinyin_abbr\"              # 拼音缩写检测(sb)\n");
                content.append("#   \"傻逼\": \"pinyin_full\"              # 完整拼音检测(shabi)\n");
                content.append("#   \"傻逼\": \"homophone:pinyin_abbr:pinyin_full\"  # 全部检测\n");
                content.append("#   \"L\": \"exact_match\"                 # 精确匹配\n");
                content.append("#   \"[我,喜,欢]\": \"homophone\"          # 词组+同音词\n");
                content.append("#   \"管理\": \"op2\"                      # OP2及以下屏蔽\n");
                content.append("\n");
                
                // 替换词
                content.append("# ────────────────────────────────────\n");
                content.append("# 替换词\n");
                content.append("# ────────────────────────────────────\n");
                content.append("# 自动将消息中的词替换为指定内容\n");
                content.append("replacements:\n");
                for (Map.Entry<String, String> entry : replacements.entrySet()) {
                    content.append("  \"").append(escapeYamlString(entry.getKey())).append("\": \"").append(escapeYamlString(entry.getValue())).append("\"\n");
                }
                content.append("# 格式: \"原词\": \"替换词\"\n");
                content.append("# 带模式: \"原词:模式\": \"替换词\"\n");
                content.append("# 模式: homophone | pinyin_abbr | pinyin_full | exact_match | op1-4\n");
                content.append("# 示例:\n");
                content.append("#   \"卧槽\": \"***\"                      # 直接替换\n");
                content.append("#   \"卧槽:homophone\": \"***\"            # 同音词替换\n");
                content.append("#   \"卧槽:pinyin_abbr\": \"***\"          # 拼音缩写替换(wc)\n");
                content.append("#   \"卧槽:pinyin_full\": \"***\"          # 完整拼音替换(wocao)\n");
                content.append("#   \"L\": \"Love\"                        # 替换L为Love\n");
                content.append("#   \"管理:op2\": \"普通工具\"              # OP2及以下替换\n");
                content.append("\n");
                
                // 防刷屏
                content.append("# ────────────────────────────────────\n");
                content.append("# 防刷屏\n");
                content.append("# ────────────────────────────────────\n");
                content.append("# 在时间窗口内发送超过指定数量的消息会触发处罚\n");
                content.append("enable_anti_spam: ").append(enableAntiSpam).append("      # 启用防刷屏\n");
                content.append("anti_spam_max_messages: ").append(antiSpamMaxMessages).append("    # 时间窗口内最大消息数\n");
                content.append("anti_spam_time_window: ").append(antiSpamTimeWindow).append("     # 时间窗口(秒)\n");
                content.append("anti_spam_action: ").append(antiSpamAction).append("       # 处罚类型: mute/kick/ban\n");
                content.append("anti_spam_action_time: ").append(antiSpamActionTime).append(" # 处罚时长 (s/min/h/d, -1=永久)\n\n");
                
                // 刷屏规则
                content.append("# ────────────────────────────────────\n");
                content.append("# 防刷屏规则 (优先于基础设置)\n");
                content.append("# ────────────────────────────────────\n");
                content.append("anti_spam_rules:\n");
                for (Map<String, String> rule : antiSpamRules) {
                    content.append("  - mode: ").append(rule.getOrDefault("mode", "block"));
                    if (rule.containsKey("time")) {
                        content.append("\n    time: ").append(rule.get("time"));
                    }
                    if (rule.containsKey("message")) {
                        content.append("\n    message: \"").append(escapeYamlString(rule.get("message"))).append("\"");
                    }
                    content.append("\n");
                }
                content.append("# 模式: block=仅屏蔽 | mute=禁言 | kick=踢出 | ban=封禁\n");
                content.append("# 示例:\n");
                content.append("#   - mode: block\n");
                content.append("#     message: \"请勿刷屏\"\n");
                content.append("#   - mode: mute\n");
                content.append("#     time: 10min\n");
                content.append("#     message: \"刷屏禁言10分钟\"\n");
                content.append("\n");
                
                // 系统数据
                content.append("# ────────────────────────────────────\n");
                content.append("# 系统数据 (自动生成,请勿修改)\n");
                content.append("# ────────────────────────────────────\n");
                content.append("muted_players:\n");
                for (Map.Entry<String, Long> entry : mutedPlayers.entrySet()) {
                    content.append("  \"").append(escapeYamlString(entry.getKey())).append("\": ").append(entry.getValue()).append("\n");
                }
                content.append("\n");
                
                // 命令说明
                content.append("# ────────────────────────────────────\n");
                content.append("# 游戏内命令 (需要OP4)\n");
                content.append("# ────────────────────────────────────\n");
                content.append("# /chatpurity reload                - 重载配置\n");
                content.append("# /chatpurity whitelist add/remove/list <词>\n");
                content.append("# /chatpurity blacklist add/remove/list <词> [模式]\n");
                content.append("# /chatpurity replacement add/remove/list <原词> <替换词>\n");
                content.append("# /chatpurity mute add/remove/list <玩家> <时间>\n");
                content.append("# 时间格式: 10s / 5min / 2h / 1d / -1(永久)\n\n");
                
                // 谐音字库说明
                content.append("# 谐音字库文件: chatpurity-homophone.yml\n");
                content.append("# 用于homophone模式的同音词检测,可编辑后reload\n");
                
                Files.writeString(configPath, content.toString(), StandardCharsets.UTF_8);
                LOGGER.info("Config saved successfully");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }
    
    /**
     * 关闭异步保存线程池（服务器停止时调用）
     */
    public void shutdown() {
        ExecutorService executor = saveExecutorRef.get();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private String escapeYamlString(String str) {
        if (str == null) return "";
        // 转义 YAML 中的特殊字符
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    private void createDefaultConfig() {
        LOGGER.info("Creating default config file at: {}", configPath);
        
        try {
            if (configPath != null) {
                Path parentDir = configPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                    LOGGER.info("Created config directory: {}", parentDir);
                }
                
                // 创建简洁清晰的配置文件
                StringBuilder configBuilder = new StringBuilder();
                configBuilder.append("# ChatPurity 聊天过滤配置\n");
                configBuilder.append("# 修改后执行 /chatpurity reload 重载\n\n");
                
                // ===== 基础设置 =====
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 基础设置\n");
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("enable_filter: true          # 总开关,关闭后所有过滤功能失效\n");
                configBuilder.append("enable_whitelist: true       # 白名单开关\n");
                configBuilder.append("enable_blacklist: true       # 黑名单开关\n");
                configBuilder.append("ignore_case: true            # 忽略大小写(对英文生效)\n");
                configBuilder.append("notify_blocked: true         # 消息被屏蔽时是否提示玩家\n");
                configBuilder.append("blocked_message: \"[ChatPurity] 你的消息已被屏蔽\"\n");
                configBuilder.append("debug_mode: false            # 调试模式,在控制台输出详细日志\n\n");
                
                // ===== 白名单 =====
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 白名单\n");
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 包含白名单词汇的消息会跳过黑名单和替换词检测\n");
                configBuilder.append("# 例如: 白名单有\"游戏\",则发送\"游戏广告\"不会被屏蔽\n");
                configBuilder.append("whitelist:\n");
                configBuilder.append("  # - \"游戏\"\n");
                configBuilder.append("  # - \"Minecraft\"\n\n");
                
                // ===== 黑名单 =====
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 黑名单\n");
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 包含黑名单词汇的消息会被屏蔽(玩家看不到)\n");
                configBuilder.append("# 格式: 词 或 词:模式\n");
                configBuilder.append("#\n");
                configBuilder.append("# 模式说明:\n");
                configBuilder.append("#   (无模式)      - 默认,消息包含该词就屏蔽\n");
                configBuilder.append("#   homophone     - 同音词检测,如\"傻逼\"会匹配\"煞笔\"等\n");
                configBuilder.append("#   pinyin_abbr   - 拼音缩写检测,如\"傻逼\"会匹配\"sb\"\n");
                configBuilder.append("#   pinyin_full   - 完整拼音检测,如\"傻逼\"会匹配\"shabi\"\n");
                configBuilder.append("#   exact_match   - 精确匹配,整条消息=该词或重复才屏蔽\n");
                configBuilder.append("#                    例: 屏蔽\"L\"则\"L\"和\"LLL\"会被屏蔽,但\"LoL\"不会\n");
                configBuilder.append("#   [词1,词2,..]  - 词组模式,消息包含所有指定词才屏蔽\n");
                configBuilder.append("#                    例: \"[我,喜,欢]\"则消息需同时含\"我\"\"喜\"\"欢\"\n");
                configBuilder.append("#   op1/op2/..    - OP等级限制\n");
                configBuilder.append("#                    op1=OP1及以下屏蔽, op2=OP2及以下屏蔽...\n");
                configBuilder.append("#\n");
                configBuilder.append("# 组合模式: 用:连接多个模式,如 傻逼:homophone:pinyin_abbr:pinyin_full:op2\n");
                configBuilder.append("# 例: \"[我,喜,欢]:homophone\" = 词组+同音词检测\n");
                configBuilder.append("blacklist:\n");
                configBuilder.append("  # 广告: \"\"\n");
                configBuilder.append("  # 傻逼: homophone:pinyin_abbr:pinyin_full\n");
                configBuilder.append("  # \"[我,喜,欢]\": \"\"\n");
                configBuilder.append("  # 管理工具: op2\n\n");
                
                // ===== 替换词 =====
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 替换词\n");
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 自动将消息中的词替换为指定内容\n");
                configBuilder.append("# 注意: 替换后消息以系统消息发送(无签名)\n");
                configBuilder.append("#\n");
                configBuilder.append("# 格式说明:\n");
                configBuilder.append("#   1. 基础格式: \"原词\": \"替换词\"\n");
                configBuilder.append("#       例如: \"卧槽\": \"***\"\n");
                configBuilder.append("#\n");
                configBuilder.append("#   2. 带模式格式: \"原词:模式\": \"替换词\"\n");
                configBuilder.append("#       例如: \"傻逼:homophone\": \"**\"\n");
                configBuilder.append("#\n");
                configBuilder.append("#   3. 词组模式: \"[词1,词2,词3]\": \"替换词\"\n");
                configBuilder.append("#       例如: \"[我,喜,欢]\": \"超级喜欢\"\n");
                configBuilder.append("#       说明: 消息需要同时包含所有指定词才会触发替换\n");
                configBuilder.append("#\n");
                configBuilder.append("#   4. 词组+模式: \"[词1,词2,词3]:模式\": \"替换词\"\n");
                configBuilder.append("#       例如: \"[我,喜,欢]:homophone\": \"超级喜欢\"\n");
                configBuilder.append("#\n");
                configBuilder.append("# 模式说明:\n");
                configBuilder.append("#   homophone     - 同音词检测模式\n");
                configBuilder.append("#                  例如: \"傻逼:homophone\" 会匹配 \"煞笔\"、\"沙比\"等\n");
                configBuilder.append("#\n");
                configBuilder.append("#   pinyin_abbr   - 拼音缩写检测模式\n");
                configBuilder.append("#                  例如: \"傻逼:pinyin_abbr\" 会匹配 \"sb\"\n");
                configBuilder.append("#\n");
                configBuilder.append("#   pinyin_full   - 完整拼音检测模式\n");
                configBuilder.append("#                  例如: \"傻逼:pinyin_full\" 会匹配 \"shabi\"\n");
                configBuilder.append("#\n");
                configBuilder.append("#   exact_match   - 精确匹配模式\n");
                configBuilder.append("#                  只有当整条消息完全匹配原词时才替换\n");
                configBuilder.append("#                  例如: \"L:exact_match\": \"*\" 会替换 \"L\" 但不会替换 \"LoL\"\n");
                configBuilder.append("#\n");
                configBuilder.append("#   op1/op2/op3/op4 - OP权限模式\n");
                configBuilder.append("#                  OPn及以下会被替换, OPn以上不会被替换\n");
                configBuilder.append("#                  例如: \"管理工具:op2\" OP1和OP2会被替换, OP3/4不会\n");
                configBuilder.append("#\n");
                configBuilder.append("#   组合模式: 用冒号连接多个模式\n");
                configBuilder.append("#       例如: \"傻逼:homophone:pinyin_abbr:pinyin_full:op2\"\n");
                configBuilder.append("#\n");
                configBuilder.append("# 注意事项:\n");
                configBuilder.append("#   1. 替换词功能在消息被屏蔽前执行\n");
                configBuilder.append("#   2. 替换后消息会重新进行黑名单检测\n");
                configBuilder.append("#   3. 白名单词汇会跳过替换词检测\n");
                configBuilder.append("#   4. 词组模式替换时会替换检测到的实际词汇组合\n");
                configBuilder.append("#       例如: \"[我,喜,欢]\": \"超级喜欢\" 会将 \"我喜欢你\" 替换为 \"超级喜欢你\"\n");
                configBuilder.append("#\n");
                configBuilder.append("replacements:\n");
                configBuilder.append("  # 基础格式示例\n");
                configBuilder.append("  # \"卧槽\": \"***\"\n");
                configBuilder.append("  #\n");
                configBuilder.append("  # 同音词模式示例\n");
                configBuilder.append("  # \"傻逼:homophone\": \"**\"\n");
                configBuilder.append("  #\n");
                configBuilder.append("  # 拼音缩写模式示例\n");
                configBuilder.append("  # \"卧槽:pinyin_abbr\": \"***\"\n");
                configBuilder.append("  #\n");
                configBuilder.append("  # 完整拼音模式示例\n");
                configBuilder.append("  # \"卧槽:pinyin_full\": \"***\"\n");
                configBuilder.append("  #\n");
                configBuilder.append("  # 组合模式示例\n");
                configBuilder.append("  # \"卧槽:homophone:pinyin_abbr:pinyin_full\": \"***\"\n");
                configBuilder.append("  #\n");
                configBuilder.append("  # 词组模式示例\n");
                configBuilder.append("  # \"[我,喜,欢]\": \"超级喜欢\"\n");
                configBuilder.append("  #\n");
                configBuilder.append("  # 精确匹配模式示例\n");
                configBuilder.append("  # \"L:exact_match\": \"*\"\n");
                configBuilder.append("  #\n");
                configBuilder.append("  # OP权限模式示例\n");
                configBuilder.append("  # \"管理工具:op2\": \"普通工具\"\n\n");
                
                // ===== 防刷屏 =====
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 防刷屏\n");
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 在时间窗口内发送超过指定数量的消息会触发处罚\n");
                configBuilder.append("enable_anti_spam: false      # 启用防刷屏\n");
                configBuilder.append("anti_spam_max_messages: 5    # 时间窗口内最大消息数\n");
                configBuilder.append("anti_spam_time_window: 5     # 时间窗口(秒)\n");
                configBuilder.append("anti_spam_action: mute       # 处罚类型: mute/kick/ban\n");
                configBuilder.append("anti_spam_action_time: 10min # 处罚时长 (s/min/h/d, -1=永久)\n\n");
                
                // ===== 高级刷屏规则 =====
                configBuilder.append("# 高级刷屏规则 (优先于上面的基础设置)\n");
                configBuilder.append("# mode: mute=禁言, kick=踢出, ban=封禁, block=仅屏蔽消息\n");
                configBuilder.append("# time: 持续时间 (mute/ban需要, -1=永久)\n");
                configBuilder.append("# message: 提示消息\n");
                configBuilder.append("anti_spam_rules:\n");
                configBuilder.append("  - mode: block\n");
                configBuilder.append("    message: \"你发送消息太快了，请稍后再试\"\n");
                configBuilder.append("  # - mode: mute\n");
                configBuilder.append("  #   time: 10min\n");
                configBuilder.append("  #   message: \"你因刷屏被禁言10分钟\"\n");
                configBuilder.append("  # - mode: kick\n");
                configBuilder.append("  #   message: \"你因刷屏被踢出服务器\"\n");
                configBuilder.append("  # - mode: ban\n");
                configBuilder.append("  #   time: 1d\n");
                configBuilder.append("  #   message: \"你因刷屏被封禁1天\"\n\n");
                
                // ===== 系统数据 =====
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 系统数据 (自动生成,请勿修改)\n");
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("muted_players:\n");
                configBuilder.append("  # 玩家名: 禁言结束时间戳\n\n");
                
                // ===== 命令说明 =====
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# 游戏内命令 (需要OP4)\n");
                configBuilder.append("# ────────────────────────────────────\n");
                configBuilder.append("# /chatpurity reload                - 重载配置\n");
                configBuilder.append("# /chatpurity whitelist add/remove/list <词>\n");
                configBuilder.append("# /chatpurity blacklist add/remove/list <词> [模式]\n");
                configBuilder.append("# /chatpurity replacement add/remove/list <原词> <替换词>\n");
                configBuilder.append("# /chatpurity mute add/remove/list <玩家> <时间>\n");
                configBuilder.append("# 时间格式: 10s / 5min / 2h / 1d / -1(永久)\n\n");
                
                // ===== 谐音字库说明 =====
                configBuilder.append("# 谐音字库文件: chatpurity-homophone.yml\n");
                configBuilder.append("# 用于homophone模式的同音词检测,可编辑后reload\n");
                
                Files.writeString(configPath, configBuilder.toString(), StandardCharsets.UTF_8);
                LOGGER.info("Default config file created successfully with detailed comments");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create default config", e);
        }
    }
    
private void loadHomophoneMap() {
        homophoneMap.clear();
        try {
            Path yamlPath = configPath.resolveSibling("chatpurity-homophone.yml");
            
            // 如果YAML文件不存在，从项目中的TXT文件创建
            if (!Files.exists(yamlPath)) {
                LOGGER.info("Homophone YAML file not found, creating from project resources");
                createHomophoneYamlFromProject(yamlPath);
            }
            
            // 从YAML配置文件加载
            if (Files.exists(yamlPath)) {
                Yaml yaml = new Yaml();
                Map<String, Object> yamlData;
                try (BufferedReader reader = Files.newBufferedReader(yamlPath, StandardCharsets.UTF_8)) {
                    yamlData = yaml.loadAs(reader, Map.class);
                }
                
                if (yamlData != null) {
                    for (Map.Entry<String, Object> entry : yamlData.entrySet()) {
                        String pinyin = entry.getKey();
                        Object value = entry.getValue();
                        
                        if (value instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> chars = (List<String>) value;
                            for (String charStr : chars) {
                                for (int i = 0; i < charStr.length(); i++) {
                                    homophoneMap.put(String.valueOf(charStr.charAt(i)), pinyin);
                                }
                            }
                        }
                    }
                    LOGGER.info("Loaded {} homophone entries from YAML: {}", homophoneMap.size(), yamlPath);
                }
            } else {
                LOGGER.warn("Homophone YAML file still not found after creation attempt");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load homophone map", e);
        }
    }
    
    private void createHomophoneYamlFromProject(Path yamlPath) {
        try {
            // 从JAR资源中加载同音字库文件
            List<String> lines = null;
            
            // 尝试从类路径资源加载
            try (var inputStream = getClass().getClassLoader().getResourceAsStream("homophone/HomophoneDictionary.txt")) {
                if (inputStream != null) {
                    lines = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
                    LOGGER.info("Loaded homophone dictionary from JAR resources");
                }
            }
            
            // 如果资源加载失败，尝试从文件系统加载（开发环境）
            if (lines == null) {
                Path[] possiblePaths = {
                    Path.of("homophone/HomophoneDictionary.txt"),
                    Path.of("src/main/resources/homophone/HomophoneDictionary.txt"),
                    Path.of("./homophone/HomophoneDictionary.txt")
                };
                
                for (Path path : possiblePaths) {
                    if (Files.exists(path)) {
                        lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                        LOGGER.info("Loaded homophone dictionary from file system: {}", path);
                        break;
                    }
                }
            }
            
            if (lines == null || lines.isEmpty()) {
                LOGGER.warn("Homophone dictionary not found, skipping YAML creation");
                return;
            }
            
            // 读取TXT文件并转换为YAML格式
            Map<String, List<String>> homophoneData = new LinkedHashMap<>();
            
            for (String line : lines) {
                if (!line.trim().isEmpty() && !line.startsWith("#")) {
                    String[] parts = line.split("\t");
                    if (parts.length >= 2) {
                        String pinyin = parts[0].trim();
                        
                        List<String> charList = new ArrayList<>();
                        // 新格式: 拼音\t字1\t字2\t字3... (每个字单独一列)
                        // 旧格式: 拼音\t字串 (第二列是连续的字符串)
                        if (parts.length > 2) {
                            // 新格式: 每个字是一列
                            for (int i = 1; i < parts.length; i++) {
                                String charStr = parts[i].trim();
                                if (!charStr.isEmpty()) {
                                    charList.add(charStr);
                                }
                            }
                        } else {
                            // 旧格式: 第二列是连续字符串
                            String characters = parts[1].trim();
                            for (int i = 0; i < characters.length(); i++) {
                                charList.add(String.valueOf(characters.charAt(i)));
                            }
                        }
                        
                        if (!charList.isEmpty()) {
                            homophoneData.put(pinyin, charList);
                        }
                    }
                }
            }
            
            // 写入YAML文件
            Yaml yaml = new Yaml();
            String yamlContent = yaml.dump(homophoneData);
            
            // 添加简洁注释头部
            StringBuilder contentBuilder = new StringBuilder();
            contentBuilder.append("# ChatPurity 谐音字库\n");
            contentBuilder.append("# 格式: 拼音: [字1, 字2, 字3, ...]\n");
            contentBuilder.append("# 修改后执行 /chatpurity reload 重载\n\n");
            contentBuilder.append(yamlContent);
            contentBuilder.append("# 结束\n");
            contentBuilder.append("# ============================================\n");
            
            Files.writeString(yamlPath, contentBuilder.toString(), StandardCharsets.UTF_8);
            LOGGER.info("Created homophone YAML file at: {} with {} entries", yamlPath, homophoneData.size());
            
        } catch (Exception e) {
            LOGGER.error("Failed to create homophone YAML file", e);
        }
    }
    
    private boolean getBoolean(Map<String, Object> data, String key, boolean defaultValue) {
        Object value = data.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            String strValue = ((String) value).trim().toLowerCase();
            if ("true".equals(strValue)) {
                return true;
            } else if ("false".equals(strValue)) {
                return false;
            } else {
                // 无效的布尔值字符串，记录警告并使用默认值
                LOGGER.warn("Invalid boolean value '{}' for key '{}', using default value {}", value, key, defaultValue);
                return defaultValue;
            }
        }
        return defaultValue;
    }
    
    private String getString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return defaultValue;
    }
    
    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return new ArrayList<>();
    }
    
    public Path getConfigPath() {
        return configPath;
    }
    
    public boolean isEnableFilter() {
        return enableFilter;
    }
    
    public boolean isEnableWhitelist() {
        return enableWhitelist;
    }
    
    public boolean isEnableBlacklist() {
        return enableBlacklist;
    }
    
    public boolean isIgnoreCase() {
        return ignoreCase;
    }
    
    public boolean isNotifyBlocked() {
        return notifyBlocked;
    }
    
    public boolean isDebugMode() {
        return debugMode;
    }
    
    public String getBlockedMessage() {
        return blockedMessage;
    }
    
    public List<String> getWhitelist() {
        return whitelist;
    }
    
    public Map<String, String> getBlacklist() {
        return blacklist;
    }
    
    public Map<String, String> getHomophoneMap() {
        return homophoneMap;
    }
    
    public Map<String, String> getReplacements() {
        return replacements;
    }

    public Map<String, Long> getMutedPlayers() {
        return mutedPlayers;
    }

    public boolean isEnableAntiSpam() {
        return enableAntiSpam;
    }

    public int getAntiSpamMaxMessages() {
        return antiSpamMaxMessages;
    }

    public int getAntiSpamTimeWindow() {
        return antiSpamTimeWindow;
    }

    public String getAntiSpamAction() {
        return antiSpamAction;
    }

    public String getAntiSpamActionTime() {
        return antiSpamActionTime;
    }

    public Map<String, Integer> getPlayerMessageCount() {
        return playerMessageCount;
    }

    public Map<String, Long> getPlayerMessageTimestamps() {
        return playerMessageTimestamps;
    }

    public List<Map<String, String>> getAntiSpamRules() {
        return antiSpamRules;
    }

    /**
     * 验证配置值的有效性
     */
    private void validateConfig() {
        // 验证防刷屏配置
        if (antiSpamMaxMessages <= 0) {
            LOGGER.warn("anti_spam_max_messages must be positive, using default value 5");
            antiSpamMaxMessages = 5;
        }
        
        if (antiSpamTimeWindow <= 0) {
            LOGGER.warn("anti_spam_time_window must be positive, using default value 5");
            antiSpamTimeWindow = 5;
        }
        
        // 验证防刷屏操作类型
        if (!antiSpamAction.equals("mute") && !antiSpamAction.equals("kick") && !antiSpamAction.equals("ban")) {
            LOGGER.warn("anti_spam_action must be 'mute', 'kick' or 'ban', using default value 'mute'");
            antiSpamAction = "mute";
        }
        
        // 验证防刷屏规则 - 使用迭代器安全删除元素
        java.util.Iterator<Map<String, String>> iterator = antiSpamRules.iterator();
        while (iterator.hasNext()) {
            Map<String, String> rule = iterator.next();
            String mode = rule.get("mode");
            if (mode != null) {
                if (!mode.equals("mute") && !mode.equals("kick") && !mode.equals("ban") && !mode.equals("block")) {
                    LOGGER.warn("Rule mode '{}' is invalid, must be 'mute', 'kick', 'ban' or 'block', removing invalid rule", mode);
                    iterator.remove();
                    continue;
                }
                
                // 验证mute和ban模式的时间参数
                if ((mode.equals("mute") || mode.equals("ban")) && !rule.containsKey("time")) {
                    LOGGER.warn("Rule with mode '{}' missing 'time' parameter, adding default time 10min", mode);
                    rule.put("time", "10min");
                }
            }
        }
        
        LOGGER.debug("Configuration validation completed");
    }

    private int getInt(Map<String, Object> data, String key, int defaultValue) {
        Object value = data.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}