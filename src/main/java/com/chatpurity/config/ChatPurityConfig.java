package com.chatpurity.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ChatPurity 模组配置类
 *
 * 使用 YAML 格式存储配置，支持以下功能：
 * - 白名单：包含白名单词汇的消息不会被屏蔽
 * - 黑名单：包含黑名单词汇的消息会被屏蔽
 * - 单词黑名单：消息中所有字符都在此列表中时才屏蔽
 * - 转换词：自动将消息中的词汇替换为其他词汇
 * - 过滤设置：控制屏蔽行为的各种选项
 */
public class ChatPurityConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    
    // ==================== 配置修正记录 ====================
    
    /**
     * 配置修正记录
     */
    private static class ConfigFixRecord {
        String configFile;
        String configKey;
        String originalValue;
        String fixedValue;
        int lineNumber;
        
        ConfigFixRecord(String configFile, String configKey, String originalValue, String fixedValue, int lineNumber) {
            this.configFile = configFile;
            this.configKey = configKey;
            this.originalValue = originalValue;
            this.fixedValue = fixedValue;
            this.lineNumber = lineNumber;
        }
    }
    
    private List<ConfigFixRecord> fixRecords = new ArrayList<>();
    
    // ==================== 基础配置 ====================
    
    private Path configPath;
    private Path configDir;
    
    /**
     * 导入的配置文件列表
     * 
     * 功能说明：
     * - 支持从多个独立的配置文件导入配置
     * - 配置文件相对于主配置文件所在目录
     * - 后导入的配置会覆盖先导入的配置
     * 
     * 使用场景：
     * - 将黑名单、白名单等配置分离到独立文件
     * - 便于管理和维护大型配置
     * 
     * 示例配置：
     * imports:
     *   - "whitelist.yml"
     *   - "blacklist.yml"
     *   - "filter.yml"
     */
    private List<String> imports = new ArrayList<>(Arrays.asList(
        "whitelist.yml",
        "blacklist.yml",
        "conversion.yml",
        "filter.yml",
        "punishment.yml",
        "logging.yml",
        "anti-bypass.yml"
    ));
    
    // ==================== 过滤列表 ====================
    
    /**
     * 白名单词汇列表
     * 
     * 功能说明：
     * - 如果消息中包含白名单中的任意词汇，该消息将被豁免所有过滤
     * - 白名单优先级最高，会跳过黑名单和单词黑名单检查
     * 
     * 使用场景：
     * - 允许某些特定词汇即使包含敏感成分也能通过
     * - 例如："正常词语" 在白名单中，即使包含"常"（假设"常"在黑名单中），消息也能通过
     * 
     * 示例配置：
     * whitelist:
     *   - "正常词语"
     *   - "合法用语"
     *   - "管理员"
     */
    private List<String> whitelist = new ArrayList<>();
    
    /**
     * 黑名单词汇列表
     * 
     * 功能说明：
     * - 如果消息中包含黑名单中的任意词汇，该消息将被屏蔽
     * - 匹配方式：不区分大小写的子字符串匹配
     * - 例如：黑名单中有"坏"，消息"你好坏人"会被屏蔽
     * 
     * 使用场景：
     * - 屏蔽特定的敏感词汇、广告词、违规词等
     * - 支持完整的词汇或单个字符
     * 
     * 示例配置：
     * blacklist:
     *   - "广告"
     *   - "刷屏"
     *   - "外挂"
     */
    private List<String> blacklist = new ArrayList<>();
    
    /**
     * 单词黑名单词汇列表
     * 
     * 功能说明：
     * - 特殊的过滤模式：只有当消息中的【所有字符】都在此列表中时才屏蔽
     * - 忽略空格和标点符号
     * - 适用于屏蔽由特定字符组成的无意义消息
     * 
     * 使用场景：
     * - 屏蔽刷屏消息如："啊啊啊啊啊"
     * - 屏蔽无意义消息如："哈哈哈哈哈"
     * - 屏蔽特定字符组成的消息
     * 
     * 示例配置：
     * wordBlacklist:
     *   - "啊"
     *   - "哈"
     *   - "呵"
     * 
     * 效果：
     * - "啊啊啊" → 被屏蔽（所有字符都在黑名单中）
     * - "哈哈啊" → 被屏蔽（所有字符都在黑名单中）
     * - "哈哈你好" → 通过（"你"和"好"不在黑名单中）
     */
    private List<String> wordBlacklist = new ArrayList<>();
    
    /**
     * 夹杂词黑名单组列表
     * 
     * 功能说明：
     * - 如果一句话内出现了某组内的所有字或词，就会直接屏蔽
     * - 用于防止一些人使用逗句号隔开被检测
     * - 每组中的字/词必须全部出现在消息中才会触发屏蔽
     * 
     * 使用场景：
     * - 防止用户用分隔符拆分敏感词，如："广，告"（原本是"广告"）
     * - 防止用户用符号隔开敏感词，如："外.挂"（原本是"外挂"）
     * - 防止用户用空格隔开敏感词，如："刷 屏"（原本是"刷屏"）
     * 
     * 示例配置：
     * mixedBlacklistGroups:
     *   - ["广", "告"]
     *   - ["外", "挂"]
     *   - ["刷", "屏"]
     * 
     * 效果：
     * - "广，告" → 被屏蔽（包含"广"和"告"）
     * - "外.挂" → 被屏蔽（包含"外"和"挂"）
     * - "刷 屏" → 被屏蔽（包含"刷"和"屏"）
     * - "广告" → 被屏蔽（包含"广"和"告"）
     * - "外挂软件" → 被屏蔽（包含"外"和"挂"）
     * - "广" → 通过（只有"广"，缺少"告"）
     * - "广告商" → 被屏蔽（包含"广"和"告"）
     * - "外星人" → 通过（只有"外"，缺少"挂"）
     */
    private List<List<String>> mixedBlacklistGroups = new ArrayList<>();
    
    /**
     * 词汇转换映射表
     * 
     * 功能说明：
     * - 自动将消息中的特定词汇替换为其他词汇
     * - 在所有过滤检查之前执行
     * - 支持多对一的替换关系
     * 
     * 使用场景：
     * - 敏感词替换：将敏感词替换为 ***
     * - 文字规范化：统一用词
     * - 趣味替换：将某些词替换为有趣的变体
     * 
     * 示例配置：
     * conversions:
     *   "旧词": "新词"
     *   "敏感词": "***"
     *   "外挂": "魔法"
     */
    private Map<String, String> conversions = new LinkedHashMap<>();
    
    /**
     * 自定义替换规则
     * 
     * 功能说明：
     * - 为不同的敏感词类型指定不同的替换字符
     * - 支持使用正则表达式匹配
     * 
     * 使用场景：
     * - 广告类词 → "[广告]"
     * - 外挂类词 → "[违规]"
     * - 刷屏类词 → "***"
     * 
     * 示例配置：
     * customReplacements:
     *   "广告": "[广告]"
     *   "外挂": "[违规]"
     *   "刷屏": "***"
     * 
     * 优先级：高于默认替换字符
     */
    private Map<String, String> customReplacements = new LinkedHashMap<>();
    
    // ==================== 基础设置 ====================
    
    /**
     * 是否启用聊天消息过滤
     * 
     * 功能说明：
     * - 总开关，关闭后所有聊天过滤功能将失效
     * - 关闭后消息仍会被正常发送，不会被屏蔽或转换
     * 
     * 使用场景：
     * - 临时关闭过滤功能进行调试
     * - 特殊活动期间临时关闭过滤
     * 
     * 默认值：true（启用）
     */
    private boolean enableFilter = true;
    
    /**
     * 是否启用合规释放模式
     * 
     * 功能说明：
     * - 开启后，不会屏蔽整条消息，而是将敏感词替换为 ***
     * - 只替换检测到的敏感词部分，其他内容正常发送
     * 
     * 使用场景：
     * - 需要保留玩家正常对话内容时
     * - 需要净化聊天内容而不是完全阻止时
     * 
     * 示例：
     * - 关闭："这是一个广告消息" → 整条消息被屏蔽
     * - 开启："这是一个广告消息" → "这是一个***消息"
     * - 开启："广？？？告此段未屏蔽" → "***此段未屏蔽"
     * 
     * 默认值：false（禁用）
     */
    private boolean enableReleaseCompliant = false;
    
    /**
     * 合规释放模式的替换字符
     * 
     * 功能说明：
     * - 替换检测到的敏感词时使用的字符
     * - 通常使用 "***" 表示被屏蔽的内容
     * 
     * 使用场景：
     * - 自定义敏感词的显示方式
     * 
     * 示例：
     * - "***" → "***"（默认）
     * - "**" → "**"
     * - "■" → "■"
     * - "[屏蔽]" → "[屏蔽]"
     * 
     * 默认值："***"
     */
    private String releaseCompliantReplacement = "***";
    
    /**
     * 是否忽略大小写进行匹配
     * 
     * 功能说明：
     * - 控制黑名单和白名单匹配时是否区分大小写
     * - 开启后 "ABC" 和 "abc" 会被视为相同
     * 
     * 使用场景：
     * - 提高过滤效果，防止通过大小写绕过
     * 
     * 默认值：true（忽略大小写）
     */
    private boolean ignoreCase = true;
    
    /**
     * 过滤器检查优先级
     * 
     * 功能说明：
     * - 控制各种过滤器的检查顺序
     * - 数字越小优先级越高（优先执行）
     * - 相同优先级的过滤器按列表顺序依次检查
     * 
     * 优先级说明：
     *   - 数字越小，检查顺序越靠前
     *   - 相同数值的过滤器具有相同优先级，按配置顺序检查
     *   - 例如：blacklist: 5 和 whitelist: 5 具有相同优先级
     * 
     * 默认优先级顺序：
     *   豁免检查 (0) → 长度检查 (1) → 转换词 (2) → 
     *   URL检查 (3) → 白名单 (4) → 黑名单 (5) → 单词黑名单 (6) → 防刷屏 (7)
     * 
     * 使用场景：
     * - 自定义检查顺序
     * - 例如：希望先检查黑名单再检查白名单，可设置为相同优先级
     * 
     * 示例配置（按优先级排序）:
     *   filterPriority:
     *     length: 1          # 优先级1（最高）
     *     conversions: 2      # 优先级2
     *     url: 3             # 优先级3
     *     whitelist: 4       # 优先级4
     *     blacklist: 4       # 优先级4（与白名单同级，按配置顺序检查）
     *     wordBlacklist: 5    # 优先级5
     *     antiSpam: 6        # 优先级6（最低）
     * 
     *   上面的配置意味着：白名单和黑名单具有相同优先级4，
     *   将按照它们在配置文件中的顺序（白名单先，黑名单后）进行检查
     */
    private Map<String, Integer> filterPriority = new LinkedHashMap<>();
    
    /**
     * 是否启用调试模式
     * 
     * 功能说明：
     * - 开启后会在控制台输出详细的过滤日志
     * - 用于排查过滤相关问题
     * 
     * 使用场景：
     * - 开发调试
     * - 排查过滤不生效的问题
     * 
     * 默认值：false（禁用）
     */
    private boolean debugMode = false;
    
    // ==================== 白名单设置 ====================
    
    /**
     * 是否启用白名单功能
     * 
     * 功能说明：
     * - 控制白名单功能是否生效
     * - 关闭后白名单检查将被跳过
     * 
     * 使用场景：
     * - 临时关闭白名单功能
     * - 只使用黑名单功能时可以关闭白名单以提高性能
     * 
     * 默认值：true（启用）
     */
    private boolean enableWhitelist = true;
    
    // ==================== 黑名单设置 ====================
    
    /**
     * 是否启用黑名单功能
     * 
     * 功能说明：
     * - 控制黑名单功能是否生效
     * - 关闭后黑名单检查将被跳过
     * 
     * 使用场景：
     * - 只使用白名单功能
     * - 临时关闭黑名单功能
     * 
     * 默认值：true（启用）
     */
    private boolean enableBlacklist = true;
    
    /**
     * 是否启用正则表达式匹配
     * 
     * 功能说明：
     * - 允许在黑名单中使用正则表达式进行更灵活的匹配
     * - 开启后黑名单项会被视为正则表达式
     * 
     * 使用场景：
     * - 屏蔽复杂的广告格式
     * - 屏蔽变体敏感词
     * 
     * 注意事项：
     * - 正则表达式匹配比普通匹配慢
     * - 需要了解正则表达式语法
     * 
     * 默认值：false（禁用）
     */
    private boolean enableRegex = false;
    
    /**
     * 黑名单匹配模式
     * 
     * 功能说明：
     * - contains: 包含即匹配（默认，消息包含黑名单词汇即屏蔽）
     * - exact: 精确匹配（消息必须完全等于黑名单词汇才屏蔽）
     * - startsWith: 前缀匹配（消息以黑名单词汇开头才屏蔽）
     * - endsWith: 后缀匹配（消息以黑名单词汇结尾才屏蔽）
     * 
     * 默认值：contains
     */
    private String blacklistMatchMode = "contains";
    
    // ==================== 单词黑名单设置 ====================
    
    /**
     * 是否启用单词黑名单功能
     * 
     * 功能说明：
     * - 控制单词黑名单功能是否生效
     * - 关闭后单词黑名单检查将被跳过
     * 
     * 使用场景：
     * - 不需要屏蔽刷屏消息时关闭
     * - 提高性能
     * 
     * 默认值：true（启用）
     */
    private boolean enableWordBlacklist = true;
    
    // ==================== 夹杂词黑名单设置 ====================
    
    /**
     * 是否启用夹杂词黑名单功能
     * 
     * 功能说明：
     * - 控制夹杂词黑名单功能是否生效
     * - 关闭后夹杂词黑名单检查将被跳过
     * 
     * 使用场景：
     * - 不需要检测分隔符拆分的敏感词时关闭
     * - 提高性能
     * 
     * 默认值：true（启用）
     */
    private boolean enableMixedBlacklist = true;
    
    /**
     * 夹杂词黑名单触发阈值
     * 
     * 功能说明：
     * - 消息中需要包含多少个组内的字/词才触发屏蔽
     * - 设为 0 或 1 表示只要有组内任意一个字/词就检查该组
     * - 设为组的大小表示必须包含组内所有字/词才触发屏蔽
     * 
     * 使用场景：
     * - 设为组大小（默认）：必须包含组内所有字/词才屏蔽，更严格
     * - 设为组大小-1：缺少一个字/词也会触发，更宽松
     * 
     * 默认值：0（自动使用组大小，即必须包含组内所有字/词）
     */
    private int mixedBlacklistThreshold = 0;
    
    /**
     * 夹杂词黑名单忽略的字符
     * 
     * 功能说明：
     * - 检测时忽略这些字符（分隔符）
     * - 可以设置标点符号、空格等
     * 
     * 使用场景：
     * - 忽略常用的分隔符
     * - 可以根据实际需要调整
     * 
     * 示例配置：
     * mixedBlacklistIgnoreChars: "，。、,. "
     * 
     * 默认值："，。、,. "（中文逗号、中文句号、中文顿号、英文逗号、英文句号、空格）
     */
    private String mixedBlacklistIgnoreChars = "，。、,. ";
    
    /**
     * 是否启用夹杂词乱序检测
     * 
     * 功能说明：
     * - 开启后，即使夹杂词组内的字/词顺序打乱也能检测到
     * - 例如：组["广", "告"]可以检测到"告广"、"广告"、"告...广"等
     * 
     * 使用场景：
     * - 防止用户通过打乱字符顺序来绕过检测
     * 
     * 示例：
     * - 关闭：组["广", "告"] 只能检测到"广...告"（顺序一致）
     * - 开启：组["广", "告"] 可以检测到"告...广"、"广...告"、"广告"等
     * 
     * 默认值：true（启用）
     */
    private boolean enableMixedDisorderDetection = true;
    
    /**
     * 单词黑名单触发阈值
     * 
     * 功能说明：
     * - 消息中需要有多少个【连续】的黑名单字符才触发屏蔽
     * - 设为 0 或 1 表示只要有黑名单字符就屏蔽
     * - 设为 3 表示需要至少 3 个连续的黑名单字符才屏蔽
     * 
     * 使用场景：
     * - 防止误杀包含少量刷屏字符的正常消息
     * 
     * 默认值：3
     */
    private int wordBlacklistThreshold = 3;
    
    // ==================== 转换词设置 ====================
    
    /**
     * 是否启用转换词功能
     * 
     * 功能说明：
     * - 控制转换词功能是否生效
     * - 关闭后消息不会被转换
     * 
     * 使用场景：
     * - 临时关闭转换功能
     * - 只使用屏蔽功能
     * 
     * 默认值：true（启用）
     */
    private boolean enableConversions = true;
    
    /**
     * 转换词匹配模式
     * 
     * 功能说明：
     * - contains: 包含即转换（默认，消息包含转换词就替换）
     * - exact: 精确匹配（消息必须完全等于转换词才替换）
     * - wholeWord: 整词匹配（只匹配完整的单词，避免误替换词组的一部分）
     * 
     * 使用场景：
     * - contains: 转换所有出现的词汇（可能误替换）
     * - exact: 只转换完全匹配的词汇
     * - wholeWord: 只转换完整单词，避免误替换（推荐）
     * 
     * 示例：
     *   - 转换词 "坏" → "好"
     *   - contains模式: "坏蛋" → "好蛋", "好坏" → "好好"（可能误替换）
     *   - exact模式: "坏" → "好", "坏蛋" → "坏蛋"（不转换）
     *   - wholeWord模式: "坏蛋" → "好蛋", "好坏" → "好坏"（只转换完整单词）
     * 
     * 默认值：contains
     */
    private String conversionMatchMode = "contains";
    
    /**
     * 转换词触发阈值
     * 
     * 功能说明：
     * - 消息中需要包含多少个转换词才执行转换
     * - 设为 0 表示只要有一个转换词就转换
     * - 设为 3 表示需要至少 3 个转换词才转换
     * 
     * 使用场景：
     * - 防止少量误匹配导致整个消息被转换
     * - 例如：只有1个词需要转换，可能忽略以避免误伤
     * 
     * 默认值：1
     */
    private int conversionThreshold = 1;
    
    /**
     * 同类词转换配置
     * 
     * 功能说明：
     * - 检测消息中是否主要由同一类词汇组成
     * - 如果消息中大部分字符都属于同一类，则进行转换
     * - 适用于识别并转换刷屏消息，同时避免误判正常消息
     * 
     * 工作原理：
     *   1. 将字符分类（如：中文、英文、数字、特殊字符、刷屏字符等）
     *   2. 统计消息中各类字符的数量和比例
     *   3. 如果某一类字符的比例超过阈值，则触发转换
     *   4. 将整个消息替换为指定的内容
     * 
     * 使用场景：
     *   - 检测并转换纯刷屏消息（如"啊啊啊啊"、"哈哈哈"）
     *   - 检测并转换纯数字刷屏（如"123456"）
     *   - 检测并转换纯英文刷屏（如"abcabc"）
     *   - 保留混合内容的正常消息（如"你好啊123"不会被转换）
     * 
     * 示例：
     *   - 消息 "啊啊啊啊啊啊啊" → 检测为"中文刷屏类" → 转换为 "..."
     *   - 消息 "哈哈哈哈哈" → 检测为"中文刷屏类" → 转换为 "..."
     *   - 消息 "你好啊" → 检测为"中文混合类" → 不转换（通过）
     *   - 消息 "哈哈哈哈123" → 检测为"混合类" → 不转换（通过）
     * 
     * 默认值：true（启用）
     */
    private boolean enableSameClassConversion = true;
    
    /**
     * 同类词转换的字符分类配置
     * 
     * 功能说明：
     * - 定义哪些字符属于同一类
     * - 可以自定义字符分类
     * - 支持正则表达式模式
     * 
     * 分类说明：
     *   - chinese_spam: 中文刷屏字符（如：啊、哈、呵、嘿）
     *   - english_spam: 英文刷屏字符（如：a、b、c 重复）
     *   - number: 数字字符
     *   - symbol: 特殊符号
     *   - custom: 自定义分类
     * 
     * 使用场景：
     *   - 添加新的刷屏字符到分类中
     *   - 自定义检测规则
     * 
     * 默认值：包含常见刷屏字符
     */
    private Map<String, List<String>> sameClassCategories = new LinkedHashMap<>();
    
    /**
     * 同类词转换阈值
     * 
     * 功能说明：
     * - 消息中同一类字符需要达到的比例才触发转换
     * - 范围：0.0 到 1.0
     * - 0.8 表示 80% 的字符属于同一类时才转换
     * 
     * 使用场景：
     *   - 提高阈值以减少误判（如设为 0.9）
     *   - 降低阈值以更严格地过滤刷屏（如设为 0.7）
     * 
     * 默认值：0.8（80%）
     */
    private double sameClassThreshold = 0.8;
    
    /**
     * 同类词转换的最小长度
     * 
     * 功能说明：
     * - 消息至少需要有多少个字符才进行同类检测
     * - 短消息（如"啊"）不进行检测，避免误判
     * 
     * 使用场景：
     *   - 避免误判短消息
     *   - 只对长消息进行检测
     * 
     * 默认值：5
     */
    private int sameClassMinLength = 5;
    
    /**
     * 同类词转换后的内容
     * 
     * 功能说明：
     * - 当检测到消息主要由同类字符组成时，替换为此内容
     * - 支持颜色代码
     * 
     * 默认值："§7..."
     */
    private String sameClassReplacement = "§7...";
    
    /**
     * 同类词转换提示信息
     * 
     * 功能说明：
     * - 当消息被同类词转换时显示的提示
     * 
     * 默认值："§e消息已优化显示"
     */
    private String sameClassConversionNotice = "§e消息已优化显示";
    
    /**
     * 是否启用同类词合并功能
     * 
     * 功能说明：
     * - 检测消息中的重复项并自动合并
     * - 支持检测以下重复模式：
     *   - 重复单个词：如 "好好好好好" → "好(×5)"
     *   - 重复词组：如 "你好你好你好" → "你好(×3)"
     *   - 重复字符：如 "aaaaa" → "a(×5)"
     *   - 重复短语：如 "哈哈哈哈哈哈" → "哈哈(×4)"
     * 
     * 工作原理：
     *   1. 检测消息中的连续重复模式
     *   2. 识别重复的内容（单个字、词、短语）
     *   3. 将重复内容合并为 "内容(×数量)" 格式
     *   4. 保留原始消息的其他部分
     * 
     * 使用场景：
     *   - 简化刷屏消息显示
     *   - 保留消息内容的同时减少视觉干扰
     *   - 区别于同类词转换（转换不改变内容，只改变格式）
     * 
     * 示例：
     *   - "好好好好好" → "好(×5)"
     *   - "啊啊啊啊啊" → "啊(×5)"
     *   - "哈哈哈哈哈哈" → "哈哈(×4)"
     *   - "你好你好你好" → "你好(×3)"
     *   - "abcabcabc" → "abc(×3)"
     *   - "你好啊123你好啊123" → "你好啊123(×2)"
     *   - "你好世界" → "你好世界"（不转换，无重复）
     * 
     * 默认值：true（启用）
     */
    private boolean enableRepeatMerge = true;
    
    /**
     * 重复合并的最小重复次数
     * 
     * 功能说明：
     *   - 内容至少重复多少次才进行合并
     *   - 例如：设为 3 表示 "好好好" 才会合并为 "好(×3)"
     *   - "好好" 不会合并
     * 
     * 使用场景：
     *   - 避免合并少量重复（如2次）
     *   - 只合并明显的刷屏行为
     * 
     * 默认值：3
     */
    private int repeatMergeMinCount = 3;
    
    /**
     * 重复合并的最大重复次数
     * 
     * 功能说明：
     *   - 超过此次数的重复不会显示具体数量
     *   - 例如：设为 10，"好×100" 会显示为 "好(×10+)"
     * 
     * 使用场景：
     *   - 避免显示过大的数字
     *   - 保持显示简洁
     * 
     * 默认值：10
     */
    private int repeatMergeMaxDisplay = 10;
    
    /**
     * 重复合并的格式模板
     * 
     * 功能说明：
     * - 控制合并后的显示格式
     * - 可用占位符：
     *   - {content} = 重复的内容
     *   - {count} = 重复次数
     *   - {countPlus} = 重复次数（超过最大值时）
     * 
     * 示例配置：
     *   - "{content}(×{count})" → "好(×5)"
     *   - "{content}[{count}]" → "好[5]"
     *   - "{content}×{count}" → "好×5"
     *   - "{content}" → 只显示内容（隐藏次数）
     * 
     * 默认值："{content}(×{count})"
     */
    private String repeatMergeFormat = "{content}(×{count})";
    
    /**
     * 重复合并的超量格式模板
     * 
     * 功能说明：
     *   - 当重复次数超过 maxDisplay 时使用的格式
     *   - 可用占位符：{content} 和 {countPlus}
     * 
     * 默认值："{content}(×{countPlus}+)"
     */
    private String repeatMergeOverflowFormat = "{content}(×{countPlus}+)";
    
    /**
     * 是否检测重复短语
     * 
     * 功能说明：
     *   - 是否检测多字符重复（如"哈哈哈"、"你好你好"）
     *   - 关闭后只检测单字符重复
     * 
     * 使用场景：
     *   - 关闭以提高性能
     *   - 只处理单字符刷屏
     * 
     * 默认值：true（启用）
     */
    private boolean enablePhraseRepeatDetection = true;
    
    /**
     * 短语检测的最小长度
     * 
     * 功能说明：
     *   - 多少个字符以上才视为短语
     *   - 例如：设为 2 表示检测 "哈哈"、"你好" 等双字短语
     * 
     * 使用场景：
     *   - 调整检测的粒度
     *   - 减少误判
     * 
     * 默认值：2
     */
    private int phraseMinLength = 2;
    
    /**
     * 短语检测的最大长度
     * 
     * 功能说明：
     *   - 最多检测多长的短语
     *   - 例如：设为 5 表示最多检测 "你好世界" 这样的5字短语
     * 
     * 使用场景：
     *   - 限制检测范围以提高性能
     *   - 避免检测过长的短语
     * 
     * 默认值：10
     */
    private int phraseMaxLength = 10;
    
    /**
     * 重复合并提示信息
     * 
     * 功能说明：
     * - 当消息被重复合并时显示的提示
     * 
     * 默认值："§e消息已合并显示"
     */
    private String repeatMergeNotice = "§e消息已合并显示";
    
    /**
     * 转换后消息的格式
     * 
     * 功能说明：
     * - 当消息被转换后，发送的系统消息格式
     * - {player} 会被替换为玩家名
     * - {message} 会被替换为转换后的消息内容
     * - 支持颜色代码
     * 
     * 示例配置：
     * convertedMessageFormat: "§f<{player}> {message}"
     */
    private String convertedMessageFormat = "§f<{player}> {message}";
    
    /**
     * 是否在转换后显示提示
     * 
     * 功能说明：
     * - 当消息被转换后，是否在行动栏显示提示
     * 
     * 默认值：false（不显示）
     */
    private boolean showConversionNotice = false;
    
    /**
     * 转换提示信息
     * 
     * 功能说明：
     * - 当消息被转换后显示的提示
     * - 支持颜色代码
     *
     * 默认值："§e[ChatPurity] 消息已自动修正"
     */
    private String conversionNoticeMessage = "§e[ChatPurity] 消息已自动修正";
    
    // ==================== 屏蔽设置 ====================
    
    /**
     * 是否在屏蔽消息时通知发送者
     * 
     * 功能说明：
     * - 当玩家的消息被屏蔽时，是否在行动栏显示提示信息
     * - 开启后玩家会看到屏蔽提示
     * 
     * 使用场景：
     * - 告知玩家消息为何没有发送成功
     * - 防止玩家困惑为何消息"消失"了
     * 
     * 默认值：true（启用）
     */
    private boolean notifyBlocked = true;
    
    /**
     * 屏蔽消息时的提示信息
     * 
     * 功能说明：
     * - 自定义屏蔽提示信息的格式
     * - 支持颜色代码（使用 § 符号）
     * 
     * 示例配置：
     * blockedMessage: "§c[警告] 您的消息包含敏感内容，已被拦截！"
     */
    private String blockedMessage = "§c[ChatPurity] 消息已被屏蔽";
    
    /**
     * 是否显示替换提示（合规释放模式）
     * 
     * 功能说明：
     * - 在合规释放模式下，给玩家提示哪些词被替换了
     * - 让玩家知道消息被修改过
     * 
     * 使用场景：
     * - 增加透明度，让玩家知道敏感词被替换
     * - 提醒玩家注意文明用语
     * 
     * 默认值：true（启用）
     */
    private boolean showReplacementNotice = true;
    
    /**
     * 替换提示信息
     * 
     * 功能说明：
     * - 当消息中的敏感词被替换时显示的提示
     * - 支持颜色代码
     * 
     * 示例配置：
     * replacementNoticeMessage: "§e您的消息中包含敏感词，已被替换"
     * 
     * 默认值："§e您的消息中包含敏感词，已被替换"
     */
    private String replacementNoticeMessage = "§e您的消息中包含敏感词，已被替换";
    
    /**
     * 屏蔽提示显示位置
     * 
     * 功能说明：
     * - action_bar: 在行动栏显示（默认，不干扰聊天）
     * - chat: 在聊天栏显示
     * - title: 在屏幕中央显示标题
     * 
     * 默认值：action_bar
     */
    private String blockedNotifyPosition = "action_bar";
    
    /**
     * 是否通知管理员严重违规
     * 
     * 功能说明：
     * - 当检测到严重违规词时，通知在线管理员
     * - 方便管理员及时处理违规行为
     * 
     * 使用场景：
     * - 需要管理员及时介入处理严重违规
     * - 监控高风险玩家行为
     * 
     * 默认值：false（禁用）
     */
    private boolean notifyAdmins = false;
    
    /**
     * 需要通知管理员的敏感词列表
     * 
     * 功能说明：
     * - 只有包含这些词的消息才会通知管理员
     * - 留空表示通知所有被屏蔽的消息
     * 
     * 使用场景：
     * - 只关注严重的违规词
     * - 减少通知频率
     * 
     * 示例配置：
     * - [] - 通知所有被屏蔽的消息
     * - ["广告", "外挂"] - 只通知包含"广告"或"外挂"的消息
     * 
     * 默认值：[]（空列表，通知所有）
     */
    private List<String> notifyWords = new ArrayList<>();
    
    /**
     * 管理员通知消息格式
     * 
     * 功能说明：
     * - 发送给管理员的通知消息格式
     * - 可用占位符：
     *   - {player}: 玩家名称
     *   - {message}: 消息内容
     *   - {reason}: 原因
     * 
     * 默认值："§c[ChatPurity] 玩家 {player} 发送了违规消息: {message} (原因: {reason})"
     */
    private String adminNotifyMessage = "§c[ChatPurity] 玩家 {player} 发送了违规消息: {message} (原因: {reason})";
    
    // ==================== 玩家警告机制设置 ====================
    
    /**
     * 是否启用玩家警告机制
     * 
     * 功能说明：
     * - 给玩家发送警告而不是直接屏蔽
     * - 达到警告次数后执行惩罚
     * 
     * 使用场景：
     * - 给玩家改正机会
     * - 减少误杀
     * 
     * 默认值：false（禁用）
     */
    private boolean enableWarning = false;
    
    /**
     * 最大警告次数
     * 
     * 功能说明：
     * - 玩家达到此警告次数后将被惩罚
     * 
     * 默认值：3
     */
    private int maxWarnings = 3;
    
    /**
     * 警告消息
     * 
     * 功能说明：
     * - 发送给玩家的警告消息
     * - 可用占位符：
     *   - {count}: 当前警告次数
     *   - {max}: 最大警告次数
     * 
     * 默认值："§c[警告] 您的消息包含敏感词，请注意文明用语 ({count}/{max})"
     */
    private String warningMessage = "§c[警告] 您的消息包含敏感词，请注意文明用语 ({count}/{max})";
    
    /**
     * 达到警告次数后的惩罚类型
     * 
     * 功能说明：
     * - mute: 禁言
     * - kick: 踢出服务器
     * - tempban: 临时封禁
     * 
     * 默认值："mute"
     */
    private String warningPunishment = "mute";
    
    // ==================== 防绕过检测设置 ====================
    
    /**
     * 是否启用防绕过检测
     * 
     * 功能说明：
     * - 检测常见的绕过方式
     * - 包括颜色代码、Unicode变体、拼音混合、谐音字、同音字等
     * 
     * 使用场景：
     * - 防止玩家通过各种方式绕过敏感词检测
     * 
     * 默认值：true（启用）
     */
    private boolean enableAntiBypass = true;
    
    /**
     * 是否检测颜色代码绕过
     * 
     * 功能说明：
     * - 检测使用 Minecraft 颜色代码（§）的绕过尝试
     * - 例如："广§r告"（使用颜色代码分隔）
     * 
     * 默认值：true（启用）
     */
    private boolean detectColorCodes = true;
    
    /**
     * 是否检测 Unicode 变体绕过
     * 
     * 功能说明：
     * - 检测使用相似字符替换的绕过尝试
     * - 例如："广吿"（使用吿代替告）
     * 
     * 默认值：true（启用）
     */
    private boolean detectUnicodeVariants = true;
    
    /**
     * 是否检测拼音混合绕过
     * 
     * 功能说明：
     * - 检测使用拼音和汉字混合的绕过尝试
     * - 例如："guang告"、"广gao"
     * 
     * 默认值：true（启用）
     */
    private boolean detectPinyinMix = true;
    
    /**
     * 是否检测谐音字绕过
     * 
     * 功能说明：
     * - 检测使用谐音字的绕过尝试
     * - 例如："光告"（光是广的谐音）
     * 
     * 默认值：true（启用）
     */
    private boolean detectHomophones = true;
    
    /**
     * 谐音字映射表
     * 
     * 功能说明：
     * - 定义常见谐音字的映射关系
     * - 格式："原字": ["谐音1", "谐音2"]
     * 
     * 示例：
     *   "广": ["光", "逛"]
     *   "告": ["告", "搞"]
     */
    private Map<String, List<String>> homophoneMap = new HashMap<>();
    
    /**
     * 拼音检测词列表
     * 
     * 功能说明：
     * - 定义需要检测的敏感词拼音
     * - 用于检测拼音混合绕过（如 "guang告"、"广gao"）
     * 
     * 示例：
     *   - "guang" → 对应 "广"
     *   - "gao" → 对应 "告"
     *   - "wai" → 对应 "外"
     *   - "gua" → 对应 "挂"
     *   - "shua" → 对应 "刷"
     *   - "ping" → 对应 "屏"
     * 
     * 默认值：常见敏感词拼音
     */
    private List<String> pinyinDetectList = new ArrayList<>(Arrays.asList(
        "guang", "gao", "wai", "gua", "shua", "ping",
        "gu", "kai", "gua", "jia", "mai", "mai",
        "qiang", "dao", "che", "pian", "zha", "pian"
    ));
    
    /**
     * 拼音到汉字的映射
     * 
     * 功能说明：
     * - 定义拼音对应的汉字，用于预处理还原
     * - 例如："guang" → "广"
     * 
     * 示例：
     *   "guang": "广"
     *   "gao": "告"
     */
    private Map<String, String> pinyinToCharMap = new HashMap<>();
    
    /**
     * Unicode 变体字符映射表
     * 
     * 功能说明：
     * - 定义 Unicode 变体字符到标准字符的映射
     * - 用于检测和还原变体字符绕过
     * 
     * 示例：
     *   "吿": "告"  (U+543F → U+544A)
     *   "哊": "有"  (U+54CA → U+6709)
     *   "哕": "有"  (U+54D5 → U+6709)
     * 
     * 扩展说明：
     * - 包含更多常见变体字符
     * - 涵盖 CJK 兼容字符和相似字形
     */
    private Map<String, String> unicodeVariantMap = new HashMap<>();
    
    // ==================== 临时封禁设置 ====================
    
    /**
     * 是否启用临时封禁功能
     * 
     * 功能说明：
     * - 对频繁违规的玩家进行临时封禁
     * - 封禁时间可配置
     * 
     * 使用场景：
     * - 处理严重违规行为
     * - 防止违规玩家继续破坏
     * 
     * 默认值：false（禁用）
     */
    private boolean enableTempBan = false;
    
    /**
     * 触发临时封禁的违规次数
     * 
     * 功能说明：
     * - 玩家违规多少次后触发临时封禁
     * 
     * 默认值：5
     */
    private int tempBanViolations = 5;
    
    /**
     * 临时封禁时长（秒）
     * 
     * 功能说明：
     * - 临时封禁的持续时间
     * - 支持时间单位：s（秒）、m（分钟）、h（小时）
     * 
     * 示例：
     * - "30s" - 30秒
     * - "5m" - 5分钟
     * - "1h" - 1小时
     * 
     * 默认值："30m"
     */
    private String tempBanDuration = "30m";
    
    /**
     * 临时封禁消息
     * 
     * 功能说明：
     * - 玩家被临时封禁时显示的消息
     * - 可用占位符：
     *   - {duration}: 封禁时长
     *   - {reason}: 封禁原因
     * 
     * 默认值："§c您已被临时封禁 {duration}，原因: {reason}"
     */
    private String tempBanMessage = "§c您已被临时封禁 {duration}，原因: {reason}";
    
    // 权限设置
    
    /**
     * 豁免过滤的权限等级
     * 
     * 功能说明：
     * - 拥有指定权限等级及以上的玩家将豁免所有过滤
     * - 0 = 所有玩家都被过滤
     * - 1-3 = 对应的 OP 等级豁免
     * - 4 = 管理员豁免
     * 
     * 使用场景：
     * - 让管理员的消息不被过滤
     * - 让特定权限组的玩家豁免
     * 
     * 默认值：4（管理员豁免）
     */
    private int bypassPermissionLevel = 4;
    
    /**
     * 豁免玩家列表（基于玩家名）
     * 
     * 功能说明：
     * - 此列表中的玩家名将被豁免所有过滤
     * - 不区分大小写
     * - 用于豁免非 OP 玩家
     * 
     * 使用场景：
     * - 特殊玩家豁免
     * - VIP 玩家豁免
     * 
     * 示例配置：
     * bypassPlayers:
     *   - "VIP_Player1"
     *   - "Special_User"
     */
    private List<String> bypassPlayers = new ArrayList<>();
    
    // ==================== 命令过滤设置 ====================
    
    /**
     * 是否过滤命令消息
     * 
     * 功能说明：
     * - 是否过滤通过命令发送的消息（如 /me、/say 等）
     * - 注意：这不包括 /msg、/tell 等私聊命令
     * 
     * 使用场景：
     * - 防止玩家通过命令绕过聊天过滤
     * 
     * 默认值：true（启用）
     */
    private boolean filterCommands = true;
    
    /**
     * 需要过滤的命令列表
     * 
     * 功能说明：
     * - 指定哪些命令的消息需要被过滤
     * - 留空表示过滤所有命令消息（如果 filterCommands 为 true）
     * 
     * 使用场景：
     * - 只过滤特定命令
     * 
     * 示例配置：
     * filteredCommands:
     *   - "me"
     *   - "say"
     *   - "broadcast"
     */
    private List<String> filteredCommands = new ArrayList<>();
    
    // ==================== 消息长度限制 ====================
    
    /**
     * 是否启用消息长度限制
     * 
     * 功能说明：
     * - 限制玩家发送的消息长度
     * 
     * 默认值：false（禁用）
     */
    private boolean enableLengthLimit = false;
    
    /**
     * 消息最大长度
     * 
     * 功能说明：
     * - 消息允许的最大字符数
     * - 超过此长度的消息将被屏蔽
     * 
     * 默认值：256
     */
    private int maxMessageLength = 256;
    
    /**
     * 消息过长时的提示信息
     * 
     * 默认值："§c消息过长，最多允许 {max} 个字符"
     */
    private String lengthLimitMessage = "§c消息过长，最多允许 {max} 个字符";
    
    // ==================== 防刷屏设置 ====================
    
    // ==================== 日志设置 ====================
    
    /**
     * 是否启用日志记录
     * 
     * 功能说明：
     * - 记录所有被屏蔽或替换的消息
     * - 方便后续分析违规情况
     * 
     * 使用场景：
     * - 统计违规词频率
     * - 分析违规玩家行为
     * - 生成违规报告
     * 
     * 默认值：false（禁用）
     */
    private boolean enableLog = false;
    
    /**
     * 日志文件路径
     * 
     * 功能说明：
     * - 指定日志文件的保存路径
     * - 相对于服务器目录
     * 
     * 默认值："chatpurity_filtered.log"
     */
    private String logPath = "chatpurity_filtered.log";
    
    /**
     * 日志中是否记录玩家名称
     * 
     * 功能说明：
     * - 控制日志中是否包含发送消息的玩家名称
     * 
     * 使用场景：
     * - 需要追踪具体玩家时启用
     * - 需要保护玩家隐私时禁用
     * 
     * 默认值：true（启用）
     */
    private boolean logPlayerName = true;
    
    /**
     * 日志中是否记录时间戳
     * 
     * 功能说明：
     * - 控制日志中是否包含时间信息
     * 
     * 默认值：true（启用）
     */
    private boolean logTimestamp = true;
    
    /**
     * 日志格式
     * 
     * 功能说明：
     * - 定义日志条目的格式
     * - 可用占位符：
     *   - {timestamp}: 时间戳
     *   - {player}: 玩家名称
     *   - {message}: 原始消息
     *   - {type}: 操作类型（blocked/replaced）
     *   - {reason}: 原因（blacklist/wordblacklist/mixedblacklist等）
     * 
     * 默认值："[{timestamp}] {player} - {type}: {message} (原因: {reason})"
     */
    private String logFormat = "[{timestamp}] {player} - {type}: {message} (原因: {reason})";
    
    /**
     * 是否启用防刷屏功能
     * 
     * 功能说明：
     * - 防止玩家快速发送重复消息
     * 
     * 默认值：false（禁用）
     */
    private boolean enableAntiSpam = false;
    
    /**
     * 防刷屏模式
     * 
     * 功能说明：
     * - same: 相同消息冷却（发送相同消息后需要等待）
     * - fast: 快速消息限制（短时间内发送过多消息）
     * - both: 同时启用相同消息冷却和快速消息限制
     * 
     * 默认值：same
     */
    private String antiSpamMode = "same";
    
    /**
     * 相同消息冷却时间（秒）
     * 
     * 功能说明：
     * - 玩家发送相同消息后需要等待的时间
     * - 0 表示不允许发送重复消息
     * 
     * 默认值：5
     */
    private int spamCooldownSeconds = 5;
    
    /**
     * 快速消息限制 - 时间窗口（秒）
     * 
     * 功能说明：
     * - 在此时间窗口内发送的消息数量不能超过 maxMessages
     * 
     * 默认值：10
     */
    private int spamTimeWindow = 10;
    
    /**
     * 快速消息限制 - 最大消息数
     * 
     * 功能说明：
     * - 在时间窗口内最多允许发送的消息数量
     * 
     * 默认值：5
     */
    private int spamMaxMessages = 5;
    
    /**
     * 刷屏惩罚时间（秒）
     * 
     * 功能说明：
     * - 触发刷屏限制后的禁言时间
     * - 0 表示只阻止当前消息，不禁言
     * 
     * 默认值：0
     */
    private int spamPunishmentTime = 0;
    
    /**
     * 刷屏提示信息
     * 
     * 默认值："§c请勿刷屏，请等待 {seconds} 秒后再试"
     */
    private String spamMessage = "§c请勿刷屏，请等待 {seconds} 秒后再试";
    
    /**
     * 刷屏惩罚提示信息
     * 
     * 默认值："§c您已被禁言 {time} 秒，请遵守聊天规则"
     */
    private String spamPunishmentMessage = "§c您已被禁言 {time} 秒，请遵守聊天规则";
    
    // ==================== URL/链接设置 ====================
    
    /**
     * 是否屏蔽包含 URL 的消息
     * 
     * 功能说明：
     * - 自动检测并屏蔽包含网址的消息
     * - 支持常见的 URL 格式检测
     * 
     * 默认值：false（不屏蔽）
     */
    private boolean blockUrls = false;
    
    /**
     * URL 屏蔽提示信息
     * 
     * 默认值："§c不允许发送链接"
     */
    private String urlBlockedMessage = "§c不允许发送链接";
    
    /**
     * URL 白名单域名
     * 
     * 功能说明：
     * - 允许的域名列表
     * - 只有这些域名的链接可以发送
     * 
     * 示例配置：
     * urlWhitelist:
     *   - "minecraft.net"
     *   - "mojang.com"
     */
    private List<String> urlWhitelist = new ArrayList<>();
    
    // ==================== 建议设置 ====================
    
    /**
     * 是否向被屏蔽的玩家显示建议
     * 
     * 功能说明：
     * - 当消息被屏蔽时，是否显示修改建议
     * - 建议会提示玩家哪些词汇可能导致了屏蔽
     * 
     * 默认值：false（不显示）
     */
    private boolean showSuggestions = false;
    
    /**
     * 建议提示信息
     * 
     * 默认值："§e建议修改消息中的敏感词汇后重试"
     */
    private String suggestionMessage = "§e建议修改消息中的敏感词汇后重试";
    
    // ==================== 构造方法 ====================
    
    public ChatPurityConfig(Path configPath) {
        this.configPath = configPath;
        load();
    }
    
    // ==================== 加载与保存 ====================
    
    /**
     * 从文件加载配置
     */
    @SuppressWarnings("unchecked")
    public void load() {
        try {
            if (configPath != null && Files.exists(configPath)) {
                // 保存配置目录路径
                configDir = configPath.getParent();
                
                String content = Files.readString(configPath);
                Yaml yaml = createYaml();
                Map<String, Object> data = yaml.load(new StringReader(content));
                
                if (data != null) {
                    // 加载导入列表
                    this.imports = getStringList(data, "imports");
                    
                    // 先加载导入的配置文件
                    if (this.imports != null && !this.imports.isEmpty()) {
                        for (String importFile : this.imports) {
                            loadImportedFile(importFile, data);
                        }
                    }
                    
                    // 加载列表
                    this.whitelist = getStringList(data, "whitelist");
                    this.blacklist = getStringList(data, "blacklist");
                    this.wordBlacklist = getStringList(data, "wordBlacklist");
                    this.mixedBlacklistGroups = getMixedBlacklistGroups(data);
                    this.conversions = getStringMap(data, "conversions");
                    
                    // 加载基础设置
                    Map<String, Object> basicSettings = getMap(data, "basic");
                    if (basicSettings != null) {
                        this.enableFilter = getBoolean(basicSettings, "enableFilter", true);
                        this.ignoreCase = getBoolean(basicSettings, "ignoreCase", true);
                        this.filterPriority = getIntMap(basicSettings, "filterPriority");
                        this.debugMode = getBoolean(basicSettings, "debugMode", false);
                    }
                    
                    // 加载过滤设置
                    Map<String, Object> filterSettings = getMap(data, "filter");
                    if (filterSettings != null) {
                        this.enableWhitelist = getBoolean(filterSettings, "enableWhitelist", true);
                        this.enableBlacklist = getBoolean(filterSettings, "enableBlacklist", true);
                        this.blacklistMatchMode = getString(filterSettings, "matchMode", "contains");
                        this.enableRegex = getBoolean(filterSettings, "enableRegex", false);
                        this.enableWordBlacklist = getBoolean(filterSettings, "enableWordBlacklist", true);
                        this.wordBlacklistThreshold = getInt(filterSettings, "wordBlacklistThreshold", 3);
                        this.enableMixedBlacklist = getBoolean(filterSettings, "enableMixedBlacklist", true);
                        this.enableReleaseCompliant = getBoolean(filterSettings, "enableReleaseCompliant", false);
                        this.releaseCompliantReplacement = getString(filterSettings, "releaseCompliantReplacement", "***");
                    }
                    
                    // 加载转换设置
                    Map<String, Object> conversionSettings = getMap(data, "conversion");
                    if (conversionSettings != null) {
                        this.enableConversions = getBoolean(conversionSettings, "enable", true);
                        this.conversionMatchMode = getString(conversionSettings, "matchMode", "contains");
                        this.conversionThreshold = getInt(conversionSettings, "threshold", 1);
                    }
                    
                    // 加载转换词高级设置（从 advanced.yml）
                    Map<String, Object> conversionAdvancedSettings = getMap(data, "conversionAdvanced");
                    if (conversionAdvancedSettings != null) {
                        // 同类词转换设置
                        Map<String, Object> sameClassSettings = getMap(conversionAdvancedSettings, "sameClass");
                        if (sameClassSettings != null) {
                            this.enableSameClassConversion = getBoolean(sameClassSettings, "enable", true);
                            this.sameClassThreshold = getDouble(sameClassSettings, "threshold", 0.8);
                            this.sameClassMinLength = getInt(sameClassSettings, "minLength", 5);
                            this.sameClassReplacement = getString(sameClassSettings, "replacement", "§7...");
                            this.sameClassConversionNotice = getString(sameClassSettings, "notice", "§e消息已优化显示");
                        }
                        
                        // 重复合并设置
                        Map<String, Object> repeatMergeSettings = getMap(conversionAdvancedSettings, "repeatMerge");
                        if (repeatMergeSettings != null) {
                            this.enableRepeatMerge = getBoolean(repeatMergeSettings, "enable", true);
                            this.repeatMergeMinCount = getInt(repeatMergeSettings, "minCount", 3);
                            this.repeatMergeMaxDisplay = getInt(repeatMergeSettings, "maxDisplay", 10);
                            this.repeatMergeFormat = getString(repeatMergeSettings, "format", "{content}(×{count})");
                            this.repeatMergeOverflowFormat = getString(repeatMergeSettings, "overflowFormat", "{content}(×{countPlus}+)");
                            this.enablePhraseRepeatDetection = getBoolean(repeatMergeSettings, "enablePhraseDetection", true);
                            this.phraseMinLength = getInt(repeatMergeSettings, "phraseMinLength", 2);
                            this.phraseMaxLength = getInt(repeatMergeSettings, "phraseMaxLength", 10);
                            this.repeatMergeNotice = getString(conversionAdvancedSettings, "repeatMergeNotice", "§e消息已合并显示");
                        }
                        
                        // 同类词字符分类
                        this.sameClassCategories = getStringListMap(conversionAdvancedSettings, "sameClassCategories");
                    }
                    
                    // 加载屏蔽设置
                    Map<String, Object> blockingSettings = getMap(data, "blocking");
                    if (blockingSettings != null) {
                        this.notifyBlocked = getBoolean(blockingSettings, "notify", true);
                        this.blockedMessage = getString(blockingSettings, "message", "§c[ChatPurity] 消息已被屏蔽");
                        this.blockedNotifyPosition = getString(blockingSettings, "notifyPosition", "action_bar");
                        this.showReplacementNotice = getBoolean(blockingSettings, "showReplacementNotice", true);
                        this.replacementNoticeMessage = getString(blockingSettings, "replacementNoticeMessage", "§e您的消息中包含敏感词，已被替换");
                    }
                    
                    // 加载权限设置
                    Map<String, Object> permissionSettings = getMap(data, "permission");
                    if (permissionSettings != null) {
                        this.bypassPermissionLevel = getInt(permissionSettings, "bypassLevel", 4);
                        this.bypassPlayers = getStringList(permissionSettings, "bypassPlayers");
                    }
                    
                    // 加载防绕过设置
                    Map<String, Object> antiBypassSettings = getMap(data, "antiBypass");
                    if (antiBypassSettings != null) {
                        this.enableAntiBypass = getBoolean(antiBypassSettings, "enable", true);
                        this.detectColorCodes = getBoolean(antiBypassSettings, "detectColorCodes", true);
                        this.detectUnicodeVariants = getBoolean(antiBypassSettings, "detectUnicodeVariants", true);
                        this.detectPinyinMix = getBoolean(antiBypassSettings, "detectPinyinMix", true);
                        this.detectHomophones = getBoolean(antiBypassSettings, "detectHomophones", true);
                        
                        // 加载拼音检测列表
                        List<String> loadedPinyinList = getStringList(antiBypassSettings, "pinyinDetectList");
                        if (loadedPinyinList != null && !loadedPinyinList.isEmpty()) {
                            this.pinyinDetectList = loadedPinyinList;
                        }
                        
                        // 加载拼音到汉字映射
                        Map<String, String> loadedPinyinMap = getStringToStringMap(antiBypassSettings, "pinyinToCharMap");
                        if (loadedPinyinMap != null && !loadedPinyinMap.isEmpty()) {
                            this.pinyinToCharMap = loadedPinyinMap;
                        } else {
                            // 默认拼音映射
                            initDefaultPinyinMap();
                        }
                        
                        // 加载 Unicode 变体映射
                        Map<String, String> loadedVariantMap = getStringToStringMap(antiBypassSettings, "unicodeVariantMap");
                        if (loadedVariantMap != null && !loadedVariantMap.isEmpty()) {
                            this.unicodeVariantMap = loadedVariantMap;
                        } else {
                            // 默认 Unicode 变体映射
                            initDefaultUnicodeVariantMap();
                        }
                        
                        // 加载谐音字映射
                        Map<String, List<String>> loadedHomophoneMap = getStringListMap(antiBypassSettings, "homophoneMap");
                        if (loadedHomophoneMap != null && !loadedHomophoneMap.isEmpty()) {
                            this.homophoneMap = loadedHomophoneMap;
                        }
                    }
                    
                    // 加载防刷屏设置
                    Map<String, Object> spamSettings = getMap(data, "spam");
                    if (spamSettings != null) {
                        this.enableAntiSpam = getBoolean(spamSettings, "enable", false);
                        this.antiSpamMode = getString(spamSettings, "mode", "same");
                        this.spamCooldownSeconds = getInt(spamSettings, "cooldownSeconds", 5);
                        this.spamMessage = getString(spamSettings, "message", "§c请勿刷屏，请等待 {seconds} 秒后再试");
                    }
                    
                    // 加载防刷屏高级设置
                    Map<String, Object> spamAdvancedSettings = getMap(data, "spamAdvanced");
                    if (spamAdvancedSettings != null) {
                        this.spamTimeWindow = getInt(spamAdvancedSettings, "timeWindow", 10);
                        this.spamMaxMessages = getInt(spamAdvancedSettings, "maxMessages", 5);
                        this.spamPunishmentTime = getInt(spamAdvancedSettings, "punishmentTime", 30);
                        this.spamPunishmentMessage = getString(spamAdvancedSettings, "punishmentMessage", "§c您已被禁言 {time} 秒，请遵守聊天规则");
                    }
                    
                    // 加载消息限制设置
                    Map<String, Object> limitSettings = getMap(data, "limit");
                    if (limitSettings != null) {
                        this.enableLengthLimit = getBoolean(limitSettings, "enableLengthLimit", false);
                        this.maxMessageLength = getInt(limitSettings, "maxLength", 256);
                        this.blockUrls = getBoolean(limitSettings, "blockUrls", false);
                        this.urlBlockedMessage = getString(limitSettings, "urlBlockedMessage", "§c[ChatPurity] 不允许发送链接");
                        this.urlWhitelist = getStringList(limitSettings, "urlWhitelist");
                    }
                    
                    // 加载建议设置
                    Map<String, Object> suggestionSettings = getMap(data, "suggestion");
                    if (suggestionSettings != null) {
                        this.showSuggestions = getBoolean(suggestionSettings, "enable", false);
                        this.suggestionMessage = getString(suggestionSettings, "message", "§e建议修改消息中的敏感词汇后重试");
                    }
                    
                    // 加载日志设置（从 advanced.yml）
                    Map<String, Object> loggingSettings = getMap(data, "logging");
                    if (loggingSettings != null) {
                        this.enableLog = getBoolean(loggingSettings, "enable", false);
                        this.logPath = getString(loggingSettings, "path", "logs/chatpurity_filtered.log");
                        this.logPlayerName = getBoolean(loggingSettings, "logPlayerName", true);
                        this.logTimestamp = getBoolean(loggingSettings, "logTimestamp", true);
                        this.logFormat = getString(loggingSettings, "format", "[{timestamp}] {player} - {type}: {message} (原因: {reason})");
                    }
                    
                    // 加载警告设置（从 advanced.yml）
                    Map<String, Object> warningSettings = getMap(data, "warning");
                    if (warningSettings != null) {
                        this.enableWarning = getBoolean(warningSettings, "enable", false);
                        this.maxWarnings = getInt(warningSettings, "maxWarnings", 3);
                        this.warningMessage = getString(warningSettings, "message", "§c[警告] 您的消息包含敏感词，请注意文明用语 ({count}/{max})");
                        this.warningPunishment = getString(warningSettings, "punishment", "mute");
                    }
                    
                    // 加载临时封禁设置（从 advanced.yml）
                    Map<String, Object> tempBanSettings = getMap(data, "tempBan");
                    if (tempBanSettings != null) {
                        this.enableTempBan = getBoolean(tempBanSettings, "enable", false);
                        this.tempBanViolations = getInt(tempBanSettings, "violations", 5);
                        this.tempBanDuration = getString(tempBanSettings, "duration", "30m");
                        this.tempBanMessage = getString(tempBanSettings, "message", "§c您已被临时封禁 {duration}，原因: {reason}");
                    }
                    
                    // 加载管理员通知设置（从 advanced.yml）
                    Map<String, Object> adminNotifySettings = getMap(data, "adminNotify");
                    if (adminNotifySettings != null) {
                        this.notifyAdmins = getBoolean(adminNotifySettings, "enable", false);
                        this.adminNotifyMessage = getString(adminNotifySettings, "message", "§c[ChatPurity] 玩家 {player} 发送了违规消息: {message} (原因: {reason})");
                    }
                    
                    // 加载消息格式设置（从 advanced.yml）
                    Map<String, Object> messageFormatSettings = getMap(data, "messageFormat");
                    if (messageFormatSettings != null) {
                        this.convertedMessageFormat = getString(messageFormatSettings, "convertedMessage", "§f<{player}> {message}");
                        this.showConversionNotice = getBoolean(messageFormatSettings, "showNotice", false);
                        this.conversionNoticeMessage = getString(messageFormatSettings, "noticeMessage", "§e[ChatPurity] 消息已自动修正");
                    }
                    
                    // 加载命令过滤设置（从 advanced.yml）
                    Map<String, Object> commandFilterSettings = getMap(data, "commandFilter");
                    if (commandFilterSettings != null) {
                        this.filterCommands = getBoolean(commandFilterSettings, "enable", true);
                        this.filteredCommands = getStringList(commandFilterSettings, "filteredCommands");
                    }
                    
                    // 加载夹杂词黑名单设置（从 lists.yml）
                    Map<String, Object> mixedBlacklistSettings = getMap(data, "mixedBlacklist");
                    if (mixedBlacklistSettings != null) {
                        this.mixedBlacklistThreshold = getInt(mixedBlacklistSettings, "threshold", 0);
                        this.mixedBlacklistIgnoreChars = getString(mixedBlacklistSettings, "ignoreChars", "，。、,. ?!@#$%^&*");
                        this.enableMixedDisorderDetection = getBoolean(mixedBlacklistSettings, "enableDisorderDetection", true);
                    }
                }
                
                // 验证并修正配置项
                validateAndFixConfig();
                
                if (debugMode) {
                    LOGGER.info("Config loaded successfully from {}", configPath);
                }
            } else {
                // 配置文件不存在，创建默认配置
                configDir = configPath.getParent();
                save();
                
                // 创建导入的配置文件
                if (imports != null && !imports.isEmpty()) {
                    for (String importFile : imports) {
                        Path importPath = configDir.resolve(importFile);
                        if (!Files.exists(importPath)) {
                            createDefaultImportFile(importFile, importPath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load config", e);
            save(); // 加载失败时保存默认配置
        }
    }
    
    /**
     * 验证并修正配置项
     * 检查所有必要配置项是否存在，不存在则使用默认值
     */
    private void validateAndFixConfig() {
        fixRecords.clear();
        
        // 基础设置验证
        if (whitelist == null) { whitelist = new ArrayList<>(); addFix("whitelist", "null", "[]", -1); }
        if (blacklist == null) { blacklist = new ArrayList<>(); addFix("blacklist", "null", "[]", -1); }
        if (wordBlacklist == null) { wordBlacklist = new ArrayList<>(); addFix("wordBlacklist", "null", "[]", -1); }
        if (mixedBlacklistGroups == null) { mixedBlacklistGroups = new ArrayList<>(); addFix("mixedBlacklistGroups", "null", "[]", -1); }
        if (conversions == null) { conversions = new LinkedHashMap<>(); addFix("conversions", "null", "{}", -1); }
        if (customReplacements == null) { customReplacements = new LinkedHashMap<>(); addFix("customReplacements", "null", "{}", -1); }
        if (filterPriority == null) { filterPriority = new LinkedHashMap<>(); addFix("filterPriority", "null", "{}", -1); }
        if (bypassPlayers == null) { bypassPlayers = new ArrayList<>(); addFix("bypassPlayers", "null", "[]", -1); }
        if (filteredCommands == null) { filteredCommands = new ArrayList<>(); addFix("filteredCommands", "null", "[]", -1); }
        if (notifyWords == null) { notifyWords = new ArrayList<>(); addFix("notifyWords", "null", "[]", -1); }
        if (urlWhitelist == null) { urlWhitelist = new ArrayList<>(); addFix("urlWhitelist", "null", "[]", -1); }
        if (homophoneMap == null) { homophoneMap = new HashMap<>(); addFix("homophoneMap", "null", "{}", -1); }
        if (sameClassCategories == null) { sameClassCategories = new LinkedHashMap<>(); addFix("sameClassCategories", "null", "{}", -1); }
        
        // 字符串设置验证
        if (blacklistMatchMode == null || blacklistMatchMode.isEmpty()) { String old = blacklistMatchMode; blacklistMatchMode = "contains"; addFix("filter.matchMode", old, "contains", -1); }
        if (blockedMessage == null || blockedMessage.isEmpty()) { String old = blockedMessage; blockedMessage = "§c[ChatPurity] 消息已被屏蔽"; addFix("blocking.message", old, blockedMessage, -1); }
        if (blockedNotifyPosition == null || blockedNotifyPosition.isEmpty()) { String old = blockedNotifyPosition; blockedNotifyPosition = "action_bar"; addFix("blocking.notifyPosition", old, "action_bar", -1); }
        if (replacementNoticeMessage == null || replacementNoticeMessage.isEmpty()) { String old = replacementNoticeMessage; replacementNoticeMessage = "§e您的消息中包含敏感词，已被替换"; addFix("blocking.replacementNoticeMessage", old, replacementNoticeMessage, -1); }
        if (adminNotifyMessage == null || adminNotifyMessage.isEmpty()) { String old = adminNotifyMessage; adminNotifyMessage = "§c[ChatPurity] 玩家 {player} 发送了违规消息: {message} (原因: {reason})"; addFix("adminNotify.message", old, adminNotifyMessage, -1); }
        if (warningMessage == null || warningMessage.isEmpty()) { String old = warningMessage; warningMessage = "§c[警告] 您的消息包含敏感词，请注意文明用语 ({count}/{max})"; addFix("warning.message", old, warningMessage, -1); }
        if (warningPunishment == null || warningPunishment.isEmpty()) { String old = warningPunishment; warningPunishment = "mute"; addFix("warning.punishment", old, "mute", -1); }
        if (tempBanDuration == null || tempBanDuration.isEmpty()) { String old = tempBanDuration; tempBanDuration = "30m"; addFix("tempBan.duration", old, "30m", -1); }
        if (tempBanMessage == null || tempBanMessage.isEmpty()) { String old = tempBanMessage; tempBanMessage = "§c您已被临时封禁 {duration}，原因: {reason}"; addFix("tempBan.message", old, tempBanMessage, -1); }
        if (logPath == null || logPath.isEmpty()) { String old = logPath; logPath = "logs/chatpurity_filtered.log"; addFix("logging.path", old, logPath, -1); }
        if (logFormat == null || logFormat.isEmpty()) { String old = logFormat; logFormat = "[{timestamp}] {player} - {type}: {message} (原因: {reason})"; addFix("logging.format", old, logFormat, -1); }
        if (spamMessage == null || spamMessage.isEmpty()) { String old = spamMessage; spamMessage = "§c请勿刷屏，请等待 {seconds} 秒后再试"; addFix("spam.message", old, spamMessage, -1); }
        if (spamPunishmentMessage == null || spamPunishmentMessage.isEmpty()) { String old = spamPunishmentMessage; spamPunishmentMessage = "§c您已被禁言 {time} 秒，请遵守聊天规则"; addFix("spamAdvanced.punishmentMessage", old, spamPunishmentMessage, -1); }
        if (suggestionMessage == null || suggestionMessage.isEmpty()) { String old = suggestionMessage; suggestionMessage = "§e建议修改消息中的敏感词汇后重试"; addFix("suggestion.message", old, suggestionMessage, -1); }
        if (conversionMatchMode == null || conversionMatchMode.isEmpty()) { String old = conversionMatchMode; conversionMatchMode = "contains"; addFix("conversion.matchMode", old, "contains", -1); }
        if (convertedMessageFormat == null || convertedMessageFormat.isEmpty()) { String old = convertedMessageFormat; convertedMessageFormat = "§f<{player}> {message}"; addFix("messageFormat.convertedMessage", old, convertedMessageFormat, -1); }
        if (conversionNoticeMessage == null || conversionNoticeMessage.isEmpty()) { String old = conversionNoticeMessage; conversionNoticeMessage = "§e[ChatPurity] 消息已自动修正"; addFix("messageFormat.noticeMessage", old, conversionNoticeMessage, -1); }
        if (sameClassReplacement == null || sameClassReplacement.isEmpty()) { String old = sameClassReplacement; sameClassReplacement = "§7..."; addFix("conversionAdvanced.sameClass.replacement", old, sameClassReplacement, -1); }
        if (sameClassConversionNotice == null || sameClassConversionNotice.isEmpty()) { String old = sameClassConversionNotice; sameClassConversionNotice = "§e消息已优化显示"; addFix("conversionAdvanced.sameClass.notice", old, sameClassConversionNotice, -1); }
        if (repeatMergeFormat == null || repeatMergeFormat.isEmpty()) { String old = repeatMergeFormat; repeatMergeFormat = "{content}(×{count})"; addFix("conversionAdvanced.repeatMerge.format", old, repeatMergeFormat, -1); }
        if (repeatMergeOverflowFormat == null || repeatMergeOverflowFormat.isEmpty()) { String old = repeatMergeOverflowFormat; repeatMergeOverflowFormat = "{content}(×{countPlus}+)"; addFix("conversionAdvanced.repeatMerge.overflowFormat", old, repeatMergeOverflowFormat, -1); }
        if (repeatMergeNotice == null || repeatMergeNotice.isEmpty()) { String old = repeatMergeNotice; repeatMergeNotice = "§e消息已合并显示"; addFix("conversionAdvanced.repeatMergeNotice", old, repeatMergeNotice, -1); }
        if (mixedBlacklistIgnoreChars == null || mixedBlacklistIgnoreChars.isEmpty()) { String old = mixedBlacklistIgnoreChars; mixedBlacklistIgnoreChars = "，。、,. ?!@#$%^&*"; addFix("mixedBlacklist.ignoreChars", old, mixedBlacklistIgnoreChars, -1); }
        if (antiSpamMode == null || antiSpamMode.isEmpty()) { String old = antiSpamMode; antiSpamMode = "same"; addFix("spam.mode", old, "same", -1); }
        
        // 数值范围验证
        if (wordBlacklistThreshold < 1) { int old = wordBlacklistThreshold; wordBlacklistThreshold = 3; addFix("filter.wordBlacklistThreshold", String.valueOf(old), "3", -1); }
        if (maxWarnings < 1) { int old = maxWarnings; maxWarnings = 3; addFix("warning.maxWarnings", String.valueOf(old), "3", -1); }
        if (tempBanViolations < 1) { int old = tempBanViolations; tempBanViolations = 5; addFix("tempBan.violations", String.valueOf(old), "5", -1); }
        if (bypassPermissionLevel < 0 || bypassPermissionLevel > 4) { int old = bypassPermissionLevel; bypassPermissionLevel = 4; addFix("permission.bypassLevel", String.valueOf(old), "4", -1); }
        if (maxMessageLength < 1) { int old = maxMessageLength; maxMessageLength = 256; addFix("limit.maxLength", String.valueOf(old), "256", -1); }
        if (spamCooldownSeconds < 0) { int old = spamCooldownSeconds; spamCooldownSeconds = 5; addFix("spam.cooldownSeconds", String.valueOf(old), "5", -1); }
        if (spamTimeWindow < 1) { int old = spamTimeWindow; spamTimeWindow = 10; addFix("spamAdvanced.timeWindow", String.valueOf(old), "10", -1); }
        if (spamMaxMessages < 1) { int old = spamMaxMessages; spamMaxMessages = 5; addFix("spamAdvanced.maxMessages", String.valueOf(old), "5", -1); }
        if (spamPunishmentTime < 0) { int old = spamPunishmentTime; spamPunishmentTime = 30; addFix("spamAdvanced.punishmentTime", String.valueOf(old), "30", -1); }
        if (conversionThreshold < 1) { int old = conversionThreshold; conversionThreshold = 1; addFix("conversion.threshold", String.valueOf(old), "1", -1); }
        if (sameClassThreshold < 0.0 || sameClassThreshold > 1.0) { double old = sameClassThreshold; sameClassThreshold = 0.8; addFix("conversionAdvanced.sameClass.threshold", String.valueOf(old), "0.8", -1); }
        if (sameClassMinLength < 1) { int old = sameClassMinLength; sameClassMinLength = 5; addFix("conversionAdvanced.sameClass.minLength", String.valueOf(old), "5", -1); }
        if (repeatMergeMinCount < 2) { int old = repeatMergeMinCount; repeatMergeMinCount = 3; addFix("conversionAdvanced.repeatMerge.minCount", String.valueOf(old), "3", -1); }
        if (repeatMergeMaxDisplay < repeatMergeMinCount) { int old = repeatMergeMaxDisplay; repeatMergeMaxDisplay = 10; addFix("conversionAdvanced.repeatMerge.maxDisplay", String.valueOf(old), "10", -1); }
        if (phraseMinLength < 1) { int old = phraseMinLength; phraseMinLength = 2; addFix("conversionAdvanced.repeatMerge.phraseMinLength", String.valueOf(old), "2", -1); }
        if (phraseMaxLength < phraseMinLength) { int old = phraseMaxLength; phraseMaxLength = 10; addFix("conversionAdvanced.repeatMerge.phraseMaxLength", String.valueOf(old), "10", -1); }
        if (mixedBlacklistThreshold < 0) { int old = mixedBlacklistThreshold; mixedBlacklistThreshold = 0; addFix("mixedBlacklist.threshold", String.valueOf(old), "0", -1); }
        
        // 输出修正报告
        printFixReport();
    }
    
    /**
     * 添加修正记录
     */
    private void addFix(String configKey, Object originalValue, Object fixedValue, int lineNumber) {
        String configFile = guessConfigFile(configKey);
        fixRecords.add(new ConfigFixRecord(configFile, configKey, 
            originalValue == null ? "null" : originalValue.toString(), 
            fixedValue == null ? "null" : fixedValue.toString(), 
            lineNumber));
    }
    
    /**
     * 根据配置项名称猜测所在的配置文件
     */
    private String guessConfigFile(String configKey) {
        // 白名单配置
        if (configKey.startsWith("whitelist")) return "whitelist.yml";
        
        // 黑名单配置
        if (configKey.startsWith("blacklist") || configKey.startsWith("wordBlacklist") || configKey.startsWith("mixedBlacklist")) return "blacklist.yml";
        
        // 转换词配置
        if (configKey.startsWith("conversion") || configKey.startsWith("repeatMerge") || configKey.startsWith("phrase") || configKey.startsWith("sameClass")) return "conversion.yml";
        
        // 过滤配置
        if (configKey.startsWith("blocking") || configKey.startsWith("permission") || configKey.startsWith("spam") || configKey.startsWith("limit") || configKey.startsWith("suggestion") || configKey.startsWith("commandFilter")) return "filter.yml";
        
        // 惩罚配置
        if (configKey.startsWith("warning") || configKey.startsWith("tempBan")) return "punishment.yml";
        
        // 日志配置
        if (configKey.startsWith("log")) return "logging.yml";
        
        // 防绕过配置
        if (configKey.startsWith("antiBypass") || configKey.startsWith("homophone") || configKey.startsWith("pinyin") || configKey.startsWith("unicodeVariant")) return "anti-bypass.yml";
        
        // 管理员通知配置
        if (configKey.startsWith("adminNotify")) return "filter.yml";
        
        // 消息格式配置
        if (configKey.startsWith("messageFormat")) return "conversion.yml";
        
        // 过滤优先级配置
        if (configKey.startsWith("filterPriority")) return "filter.yml";
        
        // URL白名单
        if (configKey.startsWith("urlWhitelist") || configKey.startsWith("notifyWords")) return "filter.yml";
        
        // 主配置
        return "main.yml";
    }
    
    /**
     * 输出修正报告
     */
    private void printFixReport() {
        if (fixRecords.isEmpty()) {
            LOGGER.info("配置验证通过，无需修正");
            return;
        }
        
        LOGGER.info("========== 配置修正报告 ==========");
        LOGGER.info("共修正 {} 个配置项:", fixRecords.size());
        
        for (ConfigFixRecord record : fixRecords) {
            String lineInfo = record.lineNumber > 0 ? " (第 " + record.lineNumber + " 行)" : "";
            LOGGER.info("  [{}]{}", record.configFile, lineInfo);
            LOGGER.info("    配置项: {}", record.configKey);
            LOGGER.info("    原值: {}", record.originalValue);
            LOGGER.info("    修正: {}", record.fixedValue);
        }
        
        LOGGER.info("提示: 请检查配置文件并修正以上配置项");
        LOGGER.info("====================================");
    }
    
    /**
     * 加载导入的配置文件并合并到主配置
     * 
     * @param importFile 导入文件名（相对于配置目录）
     * @param mainData 主配置数据（将被修改）
     */
    @SuppressWarnings("unchecked")
    private void loadImportedFile(String importFile, Map<String, Object> mainData) {
        if (configDir == null || importFile == null || importFile.trim().isEmpty()) {
            return;
        }
        
        try {
            Path importPath = configDir.resolve(importFile);
            if (!Files.exists(importPath)) {
                // 文件不存在时创建默认配置文件
                createDefaultImportFile(importFile, importPath);
                if (debugMode) {
                    LOGGER.info("Created default import file: {}", importFile);
                }
            }
            
            if (Files.exists(importPath)) {
                String content = Files.readString(importPath);
                Yaml yaml = createYaml();
                Map<String, Object> importedData = yaml.load(new StringReader(content));
                
                if (importedData != null) {
                    mergeConfig(mainData, importedData, importFile);
                    if (debugMode) {
                        LOGGER.info("Loaded import file: {}", importFile);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load import file '{}': {}", importFile, e.getMessage());
        }
    }
    
    /**
     * 创建默认的导入配置文件
     * 
     * @param importFile 导入文件名
     * @param importPath 导入文件路径
     */
    private void createDefaultImportFile(String importFile, Path importPath) {
        try {
            // 确保目录存在
            Path parent = importPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            
            String content = generateDefaultImportContent(importFile);
            if (content != null) {
                Files.writeString(importPath, content);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to create default import file '{}': {}", importFile, e.getMessage());
        }
    }
    
    /**
     * 根据文件名生成默认配置内容
     * 
     * @param importFile 导入文件名
     * @return 默认配置内容
     */
    private String generateDefaultImportContent(String importFile) {
        switch (importFile) {
            case "whitelist.yml":
                return "# 白名单配置\n" +
                       "# \n" +
                       "# 白名单中的词汇不会被过滤\n" +
                       "# 白名单优先级最高，会跳过所有其他检查\n" +
                       "# ===========================================\n\n" +
                       "# ===== 白名单设置 =====\n" +
                       "filter:\n" +
                       "  enableWhitelist: true  # 是否启用白名单功能\n\n" +
                       "# ===== 白名单词汇列表 =====\n" +
                       "# \n" +
                       "# 功能说明：\n" +
                       "# - 如果消息中包含白名单中的任意词汇，该消息将被豁免所有过滤\n" +
                       "# - 白名单优先级最高，会跳过黑名单检查\n" +
                       "# \n" +
                       "# 使用场景：\n" +
                       "# - 允许某些特定词汇即使包含敏感成分也能通过\n" +
                       "# - 例如：\"正常词语\" 在白名单中，即使包含\"常\"（假设\"常\"在黑名单中），消息也能通过\n" +
                       "# \n" +
                       "whitelist:\n" +
                       "  - \"正常词语\"\n" +
                       "  - \"合法用语\"\n" +
                       "  - \"管理员\"\n";
                       
            case "blacklist.yml":
                return "# 黑名单配置\n" +
                       "# \n" +
                       "# 黑名单中的词汇会被屏蔽或替换\n" +
                       "# ===========================================\n\n" +
                       "# ===== 黑名单设置 =====\n" +
                       "filter:\n" +
                       "  enableBlacklist: true  # 是否启用黑名单功能\n" +
                       "  enableRegex: false  # 是否启用正则表达式匹配\n" +
                       "  # 黑名单匹配模式\n" +
                       "  # - contains: 包含即匹配（默认）\n" +
                       "  # - exact: 精确匹配\n" +
                       "  # - startsWith: 前缀匹配\n" +
                       "  # - endsWith: 后缀匹配\n" +
                       "  matchMode: \"contains\"\n" +
                       "  enableWordBlacklist: true  # 是否启用单词黑名单功能\n" +
                       "  wordBlacklistThreshold: 3  # 单词黑名单触发阈值\n" +
                       "  enableMixedBlacklist: true  # 是否启用夹杂词黑名单功能\n\n" +
                       "# ===== 黑名单词汇列表 =====\n" +
                       "# \n" +
                       "# 功能说明：\n" +
                       "# - 如果消息中包含黑名单中的任意词汇，该消息将被屏蔽\n" +
                       "# - 匹配方式：不区分大小写的子字符串匹配\n" +
                       "# \n" +
                       "# 使用场景：\n" +
                       "# - 屏蔽特定的敏感词汇、广告词、违规词等\n" +
                       "# \n" +
                       "blacklist:\n" +
                       "  - \"广告\"\n" +
                       "  - \"刷屏\"\n" +
                       "  - \"外挂\"\n\n" +
                       "# ===== 单词黑名单词汇列表 =====\n" +
                       "# \n" +
                       "# 功能说明：\n" +
                       "# - 只有当消息中的所有字符都在此列表中时才屏蔽\n" +
                       "# - 忽略空格和标点符号\n" +
                       "# \n" +
                       "# 使用场景：\n" +
                       "# - 屏蔽刷屏消息如：\"啊啊啊啊啊\"\n" +
                       "# \n" +
                       "wordBlacklist:\n" +
                       "  - \"啊\"\n" +
                       "  - \"哈\"\n" +
                       "  - \"呵\"\n\n" +
                       "# ===== 夹杂词黑名单组 =====\n" +
                       "# \n" +
                       "# 功能说明：\n" +
                       "# - 如果消息中包含组内的所有字/词，将被屏蔽\n" +
                       "# - 用于检测用分隔符拆分的敏感词\n" +
                       "# \n" +
                       "# 使用场景：\n" +
                       "# - 检测 \"广，告\" 这样的拆分词\n" +
                       "# \n" +
                       "mixedBlacklistGroups:\n" +
                       "  - [\"广\", \"告\"]\n" +
                       "  - [\"外\", \"挂\"]\n" +
                       "  - [\"刷\", \"屏\"]\n\n" +
                       "# ===== 夹杂词黑名单设置 =====\n" +
                       "mixedBlacklist:\n" +
                       "  threshold: 0  # 触发阈值（0=自动使用组大小）\n" +
                       "  ignoreChars: \"，。、,. ?!@#$%^&*\"  # 忽略的字符（分隔符）\n" +
                       "  enableDisorderDetection: true  # 是否检测乱序\n";
                       
            case "conversion.yml":
                return "# 转换词配置\n" +
                       "# \n" +
                       "# 自动将消息中的词汇替换为其他词汇\n" +
                       "# ===========================================\n\n" +
                       "# ===== 转换词设置 =====\n" +
                       "conversion:\n" +
                       "  enable: true  # 是否启用转换词功能\n" +
                       "  # 转换词匹配模式\n" +
                       "  # - contains: 包含即转换（默认）\n" +
                       "  # - exact: 精确匹配\n" +
                       "  # - wholeWord: 整词匹配\n" +
                       "  matchMode: \"contains\"\n" +
                       "  threshold: 1  # 触发阈值\n\n" +
                       "# ===== 合规释放模式设置 =====\n" +
                       "filter:\n" +
                       "  enableReleaseCompliant: false  # 是否启用合规释放模式\n" +
                       "  releaseCompliantReplacement: \"***\"  # 替换字符\n\n" +
                       "# ===== 转换词映射表 =====\n" +
                       "# \n" +
                       "# 功能说明：\n" +
                       "# - 自动将消息中的特定词汇替换为其他词汇\n" +
                       "# - 执行顺序：在所有过滤检查之前执行\n" +
                       "# \n" +
                       "# 使用场景：\n" +
                       "# - 敏感词替换：将敏感词替换为 ***\n" +
                       "# - 文字规范化：统一用词\n" +
                       "# - 趣味替换：将某些词替换为有趣的变体\n" +
                       "# \n" +
                       "conversions:\n" +
                       "  \"广告\": \"***\"\n" +
                       "  \"外挂\": \"***\"\n\n" +
                       "# ===== 自定义替换规则 =====\n" +
                       "# \n" +
                       "# 功能说明：\n" +
                       "# - 为不同的敏感词设置不同的替换字符\n" +
                       "# - 优先级高于默认替换字符\n" +
                       "# \n" +
                       "# 使用场景：\n" +
                       "# - 广告 → [广告]\n" +
                       "# - 外挂 → [违规]\n" +
                       "# \n" +
                       "customReplacements:\n" +
                       "  \"广告\": \"[广告]\"\n" +
                       "  \"外挂\": \"[违规]\"\n\n" +
                       "# ===== 转换词高级设置 =====\n" +
                       "conversionAdvanced:\n" +
                       "  # 同类词转换设置\n" +
                       "  sameClass:\n" +
                       "    enable: true  # 是否启用同类词转换\n" +
                       "    threshold: 0.8  # 触发阈值（0.0-1.0）\n" +
                       "    minLength: 5  # 最小消息长度\n" +
                       "    replacement: \"§7...\"  # 替换内容\n" +
                       "    notice: \"§e消息已优化显示\"  # 转换提示消息\n" +
                       "  # 重复合并设置\n" +
                       "  repeatMerge:\n" +
                       "    enable: true  # 是否启用重复合并\n" +
                       "    minCount: 3  # 最小重复次数\n" +
                       "    maxDisplay: 10  # 最大显示次数\n" +
                       "    format: \"{content}(×{count})\"  # 正常显示格式\n" +
                       "    overflowFormat: \"{content}(×{countPlus}+)\"  # 超量显示格式\n" +
                       "    enablePhraseDetection: true  # 是否检测短语重复\n" +
                       "    phraseMinLength: 2  # 短语最小长度\n" +
                       "    phraseMaxLength: 10  # 短语最大长度\n" +
                       "  # 同类词字符分类\n" +
                       "  sameClassCategories:\n" +
                       "    数字: [\"0\", \"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\", \"8\", \"9\"]\n" +
                       "    字母: [\"a\", \"b\", \"c\", \"d\", \"e\", \"f\", \"g\", \"h\", \"i\", \"j\", \"k\", \"l\", \"m\", \"n\", \"o\", \"p\", \"q\", \"r\", \"s\", \"t\", \"u\", \"v\", \"w\", \"x\", \"y\", \"z\"]\n" +
                       "  repeatMergeNotice: \"§e消息已合并显示\"  # 重复合并提示消息\n";
                       
            case "filter.yml":
                return "# 过滤设置\n\n" +
                       "# ===== 屏蔽提示设置 =====\n" +
                       "blocking:\n" +
                       "  notify: true  # 屏蔽消息时是否通知玩家\n" +
                       "  message: \"§c[ChatPurity] 消息已被屏蔽\"  # 通知内容\n" +
                       "  # notifyPosition 通知显示位置:\n" +
                       "  #   action_bar - 行动栏(屏幕下方物品栏上方,推荐)\n" +
                       "  #   chat - 聊天栏(会刷屏,不推荐)\n" +
                       "  #   title - 屏幕中央大字(比较显眼)\n" +
                       "  notifyPosition: \"action_bar\"\n" +
                       "  showReplacementNotice: true  # 替换敏感词后是否提示玩家\n" +
                       "  replacementNoticeMessage: \"§e您的消息中包含敏感词，已被替换\"\n\n" +
                       "# ===== 权限设置 =====\n" +
                       "permission:\n" +
                       "  # bypassLevel 豁免权限等级:\n" +
                       "  #   0 - 无豁免,所有人都被过滤\n" +
                       "  #   1-3 - 对应OP等级的玩家豁免\n" +
                       "  #   4 - 只有管理员(OP)豁免(推荐)\n" +
                       "  bypassLevel: 4\n" +
                       "  bypassPlayers: []  # 按玩家名豁免 例: [\"Steve\", \"Alex\"]\n\n" +
                       "# ===== 命令过滤 =====\n" +
                       "# 是否过滤通过命令发送的消息\n" +
                       "# 例: /me 在跳舞 会显示\"Steve 在跳舞\"\n" +
                       "commandFilter:\n" +
                       "  enable: true  # 是否过滤命令消息\n" +
                       "  filteredCommands: []  # 指定过滤哪些命令(空=过滤全部)\n" +
                       "                       # 例: [\"me\", \"say\", \"broadcast\"]\n\n" +
                       "# ===== 消息长度限制 =====\n" +
                       "limit:\n" +
                       "  enableLengthLimit: true  # 是否启用\n" +
                       "  maxLength: 256  # 最大字符数\n" +
                       "  blockUrls: false  # 是否屏蔽包含链接的消息\n" +
                       "  urlBlockedMessage: \"§c[ChatPurity] 不允许发送链接\"  # URL屏蔽提示\n" +
                       "  urlWhitelist: []  # 允许的域名 例: [\"minecraft.net\", \"mojang.com\"]\n\n" +
                       "# ===== 建议提示 =====\n" +
                       "suggestion:\n" +
                       "  enable: true  # 是否在屏蔽时显示建议\n" +
                       "  message: \"§7[提示] 请文明用语，友善交流\"\n\n" +
                       "# ===== 防刷屏 =====\n" +
                       "spam:\n" +
                       "  enable: false  # 是否启用防刷屏\n" +
                       "  # mode 模式:\n" +
                       "  #   same - 相同消息冷却: 发送相同消息后需等待一段时间\n" +
                       "  #          例: 连续发\"哈哈\"会被阻止\n" +
                       "  #   fast - 快速消息限制: 短时间内发送太多消息会被阻止\n" +
                       "  #          例: 10秒内发了10条消息会被阻止\n" +
                       "  #   both - 同时启用以上两种\n" +
                       "  mode: \"same\"\n" +
                       "  cooldownSeconds: 5  # 相同消息冷却时间(秒)\n" +
                       "  message: \"§c请勿刷屏，请等待 {seconds} 秒后再试\"\n\n" +
                       "# ===== 防刷屏高级设置 =====\n" +
                       "spamAdvanced:\n" +
                       "  timeWindow: 10  # 时间窗口(秒),fast模式用\n" +
                       "  maxMessages: 5  # 时间窗口内最大消息数\n" +
                       "  punishmentTime: 30  # 违规后禁言时间(秒),0=只阻止不惩罚\n" +
                       "  punishmentMessage: \"§c您已被禁言 {time} 秒，请遵守聊天规则\"\n";
                       
            case "punishment.yml":
                return "# 惩罚设置\n\n" +
                       "# ===== 警告机制 =====\n" +
                       "# 玩家违规时先警告,达到次数后惩罚\n" +
                       "warning:\n" +
                       "  enable: true  # 是否启用警告机制\n" +
                       "  maxWarnings: 3  # 最大警告次数,超过后执行惩罚\n" +
                       "  # 警告消息,可用占位符:\n" +
                       "  #   {count} - 当前警告次数\n" +
                       "  #   {max} - 最大警告次数\n" +
                       "  message: \"§c[警告] 您的消息包含敏感词，请注意文明用语 ({count}/{max})\"\n" +
                       "  # punishment 达到警告上限后的惩罚:\n" +
                       "  #   mute - 禁言(不能发消息)\n" +
                       "  #   kick - 踢出服务器\n" +
                       "  #   tempban - 临时封禁(一段时间内无法进入服务器)\n" +
                       "  punishment: \"mute\"\n\n" +
                       "# ===== 临时封禁 =====\n" +
                       "# 违规次数达到阈值后临时封禁玩家\n" +
                       "tempBan:\n" +
                       "  enable: true  # 是否启用临时封禁\n" +
                       "  violations: 5  # 触发封禁需要的违规次数\n" +
                       "  # duration 封禁时长:\n" +
                       "  #   格式: 数字+单位\n" +
                       "  #   单位: s=秒, m=分钟, h=小时, d=天\n" +
                       "  #   例: \"30s\"=30秒, \"5m\"=5分钟, \"1h\"=1小时, \"1d\"=1天\n" +
                       "  duration: \"30m\"\n" +
                       "  # 封禁提示,可用占位符:\n" +
                       "  #   {duration} - 封禁时长\n" +
                       "  #   {reason} - 封禁原因\n" +
                       "  message: \"§c您已被临时封禁 {duration}，原因: {reason}\"\n";
                       
            case "logging.yml":
                return "# 日志设置\n" +
                       "# 记录被屏蔽或替换的消息,方便管理员查看\n\n" +
                       "logging:\n" +
                       "  enable: true  # 是否启用日志记录\n" +
                       "  path: \"logs/chatpurity_filtered.log\"  # 日志文件保存路径\n" +
                       "  logPlayerName: true  # 是否记录玩家名称\n" +
                       "  logTimestamp: true  # 是否记录时间戳\n" +
                       "  # format 日志格式,可用占位符:\n" +
                       "  #   {timestamp} - 时间戳(如: 2024-01-01 12:00:00)\n" +
                       "  #   {player} - 玩家名称\n" +
                       "  #   {message} - 原始消息内容\n" +
                       "  #   {type} - 操作类型(blocked=屏蔽/replaced=替换)\n" +
                       "  #   {reason} - 屏蔽原因(如: blacklist/wordblacklist)\n" +
                       "  format: \"[{timestamp}] {player} - {type}: {message} (原因: {reason})\"\n";
                       
            case "anti-bypass.yml":
                return "# 防绕过设置\n" +
                       "# 检测玩家试图绕过过滤的各种方式\n\n" +
                       "antiBypass:\n" +
                       "  enable: true  # 是否启用防绕过检测\n\n" +
                       "  # detectColorCodes 检测颜色代码绕过\n" +
                       "  # 玩家可能用Minecraft颜色代码(§)拆分敏感词\n" +
                       "  # 例: \"广§r告\" 中间加了颜色代码,实际显示\"广告\"\n" +
                       "  detectColorCodes: true\n\n" +
                       "  # detectUnicodeVariants 检测Unicode变体绕过\n" +
                       "  # 玩家可能用外观相似的字符替换\n" +
                       "  # 例: 用全角字母或相似字符替换\n" +
                       "  detectUnicodeVariants: true\n\n" +
                       "  # detectPinyinMix 检测拼音混合绕过\n" +
                       "  # 玩家可能用拼音和汉字混合\n" +
                       "  # 例: \"guang告\" \"广gao\"\n" +
                       "  detectPinyinMix: true\n\n" +
                       "  # detectHomophones 检测谐音字绕过\n" +
                       "  # 玩家可能用发音相同的字替换\n" +
                       "  # 例: \"光告\"代替\"广告\"(光是广的谐音)\n" +
                       "  detectHomophones: true\n\n" +
                       "  # homophoneMap 谐音字映射表\n" +
                       "  # 定义每个字对应的谐音字,用于检测\n" +
                       "  # 格式: {\"原字\": [\"谐音1\", \"谐音2\"]}\n" +
                       "  # 例: {\"广\": [\"光\", \"逛\"], \"告\": [\"搞\"]}\n" +
                       "  # 效果: \"光告\"会被检测为\"广告\"\n" +
                       "  homophoneMap: {}\n";
                       
            default:
                return null;
        }
    }
    
    /**
     * 合并导入的配置到主配置
     * 
     * 合并规则：
     * - 列表：追加元素（不重复）
     * - Map：递归合并，导入的值覆盖主配置的值
     * - 基本类型：导入的值覆盖主配置的值
     * 
     * @param mainData 主配置数据（将被修改）
     * @param importedData 导入的配置数据
     * @param importFile 导入文件名（用于日志）
     */
    @SuppressWarnings("unchecked")
    private void mergeConfig(Map<String, Object> mainData, Map<String, Object> importedData, String importFile) {
        for (Map.Entry<String, Object> entry : importedData.entrySet()) {
            String key = entry.getKey();
            Object importValue = entry.getValue();
            Object mainValue = mainData.get(key);
            
            if (mainValue == null) {
                // 主配置中不存在此键，直接添加
                mainData.put(key, importValue);
            } else if (mainValue instanceof List && importValue instanceof List) {
                // 列表合并：追加不重复的元素
                List<Object> mainList = new ArrayList<>((List<?>) mainValue);
                for (Object item : (List<?>) importValue) {
                    if (!mainList.contains(item)) {
                        mainList.add(item);
                    }
                }
                mainData.put(key, mainList);
            } else if (mainValue instanceof Map && importValue instanceof Map) {
                // Map 递归合并
                Map<String, Object> mainMap = new LinkedHashMap<>((Map<String, Object>) mainValue);
                Map<String, Object> importMap = (Map<String, Object>) importValue;
                for (Map.Entry<String, Object> subEntry : importMap.entrySet()) {
                    String subKey = subEntry.getKey();
                    Object subImportValue = subEntry.getValue();
                    Object subMainValue = mainMap.get(subKey);
                    
                    if (subMainValue == null) {
                        mainMap.put(subKey, subImportValue);
                    } else if (subMainValue instanceof List && subImportValue instanceof List) {
                        // 嵌套列表合并
                        List<Object> subList = new ArrayList<>((List<?>) subMainValue);
                        for (Object item : (List<?>) subImportValue) {
                            if (!subList.contains(item)) {
                                subList.add(item);
                            }
                        }
                        mainMap.put(subKey, subList);
                    } else if (subMainValue instanceof Map && subImportValue instanceof Map) {
                        // 嵌套 Map 合并
                        Map<String, Object> nestedMain = new LinkedHashMap<>((Map<String, Object>) subMainValue);
                        Map<String, Object> nestedImport = (Map<String, Object>) subImportValue;
                        for (Map.Entry<String, Object> nestedEntry : nestedImport.entrySet()) {
                            if (!nestedMain.containsKey(nestedEntry.getKey())) {
                                nestedMain.put(nestedEntry.getKey(), nestedEntry.getValue());
                            } else if (nestedMain.get(nestedEntry.getKey()) instanceof List && nestedEntry.getValue() instanceof List) {
                                List<Object> nestedList = new ArrayList<>((List<?>) nestedMain.get(nestedEntry.getKey()));
                                for (Object item : (List<?>) nestedEntry.getValue()) {
                                    if (!nestedList.contains(item)) {
                                        nestedList.add(item);
                                    }
                                }
                                nestedMain.put(nestedEntry.getKey(), nestedList);
                            } else {
                                nestedMain.put(nestedEntry.getKey(), nestedEntry.getValue());
                            }
                        }
                        mainMap.put(subKey, nestedMain);
                    } else {
                        // 基本类型覆盖
                        mainMap.put(subKey, subImportValue);
                    }
                }
                mainData.put(key, mainMap);
            } else {
                // 基本类型或类型不匹配，导入值覆盖主值
                mainData.put(key, importValue);
            }
        }
    }

    /**
     * 保存配置到文件
     */
    public void save() {
        try {
            if (configPath == null) {
                LOGGER.warn("Config path is null, cannot save");
                return;
            }
            
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            
            String yamlContent = generateYaml();
            Files.writeString(configPath, yamlContent);
            
            if (debugMode) {
                LOGGER.info("Config saved successfully to {}", configPath);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * 生成 YAML 配置文件内容
     */
    private String generateYaml() {
        StringBuilder sb = new StringBuilder();

        sb.append("# ChatPurity v2.0.0 配置文件\n\n");
        
        // 导入列表
        sb.append("imports:\n");
        if (imports.isEmpty()) {
            sb.append("  # - \"whitelist.yml\"\n");
            sb.append("  # - \"blacklist.yml\"\n");
            sb.append("  # - \"conversion.yml\"\n");
            sb.append("  # - \"filter.yml\"\n");
            sb.append("  # - \"punishment.yml\"\n");
            sb.append("  # - \"logging.yml\"\n");
            sb.append("  # - \"anti-bypass.yml\"\n");
        } else {
            for (String importFile : imports) {
                sb.append("  - \"").append(escapeYamlString(importFile)).append("\"\n");
            }
        }
        sb.append("\n");
        
        // 如果启用了多文件配置（imports 不为空），主文件只保留 imports 和 basic 设置
        if (!imports.isEmpty()) {
            sb.append("basic:\n");
            sb.append("  enableFilter: ").append(enableFilter).append("  # 总开关\n");
            sb.append("  ignoreCase: ").append(ignoreCase).append("  # 忽略大小写\n");
            sb.append("  debugMode: ").append(debugMode).append("  # 调试模式\n");
            return sb.toString();
        }
        
        // 以下是单文件模式的完整配置（imports 为空时）
        
        // 白名单
        sb.append("# ==================== 白名单词汇列表 ====================\n");
        sb.append("# 功能: 包含白名单词汇的消息将被豁免所有过滤检查\n");
        sb.append("# 优先级: 最高（在黑名单检查之前）\n");
        sb.append("# 匹配方式: 子字符串匹配\n");
        sb.append("# \n");
        sb.append("# 使用场景:\n");
        sb.append("#   - 允许特定词汇即使包含敏感成分也能通过\n");
        sb.append("#   - 为特定术语创建豁免\n");
        sb.append("# ===========================================\n");
        sb.append("whitelist:\n");
        appendList(sb, whitelist, "  - \"");
        sb.append("\n");
        
        // 黑名单
        sb.append("# ==================== 黑名单词汇列表 ====================\n");
        sb.append("# 功能: 包含黑名单词汇的消息将被屏蔽\n");
        sb.append("# 优先级: 次高（在白名单检查之后）\n");
        sb.append("# 匹配方式: 根据 blacklistSettings.matchMode 设置\n");
        sb.append("# \n");
        sb.append("# 使用场景:\n");
        sb.append("#   - 屏蔽敏感词汇、广告词、违规词\n");
        sb.append("# ===========================================\n");
        sb.append("blacklist:\n");
        appendList(sb, blacklist, "  - \"");
        sb.append("\n");
        
        // 单词黑名单
        sb.append("# ==================== 单词黑名单词汇列表 ====================\n");
        sb.append("# 功能: 当消息中的【所有字符】都在此列表中时才屏蔽\n");
        sb.append("# 特点: 忽略空格和标点符号，用于屏蔽刷屏消息\n");
        sb.append("# \n");
        sb.append("# 使用场景:\n");
        sb.append("#   - 屏蔽刷屏消息如: \"啊啊啊啊\"\n");
        sb.append("#   - 屏蔽无意义消息如: \"哈哈哈哈\"\n");
        sb.append("# \n");
        sb.append("# 触发条件: 需要连续 N 个字符在黑名单中（N 由 wordBlacklistSettings.threshold 设置）\n");
        sb.append("# ===========================================\n");
        sb.append("wordBlacklist:\n");
        appendList(sb, wordBlacklist, "  - \"");
        sb.append("\n");
        
        // 夹杂词黑名单组
        sb.append("# ==================== 夹杂词黑名单组 ====================\n");
        sb.append("# 功能: 检测被分隔符拆分的敏感词\n");
        sb.append("# \n");
        sb.append("# 工作原理:\n");
        sb.append("#   如果一句话内出现了某组内的所有字或词，就会直接屏蔽\n");
        sb.append("#   用于防止一些人使用逗句号隔开被检测\n");
        sb.append("# \n");
        sb.append("# 使用场景:\n");
        sb.append("#   - 防止用户用分隔符拆分敏感词，如：\"广，告\"（原本是\"广告\"）\n");
        sb.append("#   - 防止用户用符号隔开敏感词，如：\"外.挂\"（原本是\"外挂\"）\n");
        sb.append("#   - 防止用户用空格隔开敏感词，如：\"刷 屏\"（原本是\"刷屏\"）\n");
        sb.append("# \n");
        sb.append("# 触发条件: 消息中包含组内所有字/词（由 mixedBlacklistSettings.threshold 设置）\n");
        sb.append("# ===========================================\n");
        sb.append("mixedBlacklistGroups:\n");
        if (mixedBlacklistGroups.isEmpty()) {
            sb.append("  # 格式: 每一组用方括号括起来，组内用逗号分隔\n");
            sb.append("  # 示例:\n");
            sb.append("  #   - [\"广\", \"告\"]\n");
            sb.append("  #   - [\"外\", \"挂\"]\n");
            sb.append("  #   - [\"刷\", \"屏\"]\n");
        } else {
            for (List<String> group : mixedBlacklistGroups) {
                sb.append("  - [");
                for (int i = 0; i < group.size(); i++) {
                    sb.append("\"").append(escapeYamlString(group.get(i))).append("\"");
                    if (i < group.size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append("]\n");
            }
        }
        sb.append("\n");
        
        // 转换词
        sb.append("# ==================== 转换词映射表 ====================\n");
        sb.append("# 功能: 自动将消息中的词汇替换为其他词汇\n");
        sb.append("# 执行顺序: 在所有过滤检查之前执行\n");
        sb.append("# \n");
        sb.append("# 使用场景:\n");
        sb.append("#   - 敏感词替换: 将敏感词替换为 ***\n");
        sb.append("#   - 文字规范化: 统一用词\n");
        sb.append("#   - 趣味替换: 将某些词替换为有趣的变体\n");
        sb.append("# ===========================================\n");
        sb.append("conversions:\n");
        appendMap(sb, conversions, "  \"");
        sb.append("\n");
        
        // 自定义替换规则
        sb.append("# ==================== 自定义替换规则 ====================\n");
        sb.append("# 为不同的敏感词类型指定不同的替换字符\n");
        sb.append("# ===========================================\n");
        sb.append("customReplacements:\n");
        sb.append("  # 格式: \"敏感词\": \"替换内容\"\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   为不同的敏感词类型指定不同的替换字符\n");
        sb.append("  #   支持使用正则表达式匹配\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 广告类词 → \"[广告]\"\n");
        sb.append("  #   - 外挂类词 → \"[违规]\"\n");
        sb.append("  #   - 刷屏类词 → \"***\"\n");
        sb.append("  # \n");
        sb.append("  # 示例配置:\n");
        sb.append("  #   \"广告\": \"[广告]\"\n");
        sb.append("  #   \"外挂\": \"[违规]\"\n");
        sb.append("  #   \"刷屏\": \"***\"\n");
        sb.append("  # \n");
        sb.append("  # 优先级: 高于默认替换字符\n");
        if (customReplacements.isEmpty()) {
            sb.append("  # 广告: \"[广告]\"\n");
            sb.append("  # 外挂: \"[违规]\"\n");
        } else {
            appendMap(sb, customReplacements, "  \"");
        }
        sb.append("\n");
        
        // 基础设置
        sb.append("# ==================== 基础设置 ====================\n");
        sb.append("# 控制过滤系统的基本行为\n");
        sb.append("# ===========================================\n");
        sb.append("basic:\n");
        sb.append("  # 是否启用聊天消息过滤（总开关）\n");
        sb.append("  # 关闭后所有过滤功能将失效\n");
        sb.append("  # 默认: true\n");
        sb.append("  enableFilter: ").append(enableFilter).append("\n\n");
        
        sb.append("  # 是否启用合规释放模式\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   开启后，不会屏蔽整条消息，而是将敏感词替换为 ***\n");
        sb.append("  #   只替换检测到的敏感词部分，其他内容正常发送\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 需要保留玩家正常对话内容时\n");
        sb.append("  #   - 需要净化聊天内容而不是完全阻止时\n");
        sb.append("  # \n");
        sb.append("  # 示例:\n");
        sb.append("  #   - 关闭: \"这是一个广告消息\" → 整条消息被屏蔽\n");
        sb.append("  #   - 开启: \"这是一个广告消息\" → \"这是一个***消息\"\n");
        sb.append("  #   - 开启: \"广？？？告此段未屏蔽\" → \"***此段未屏蔽\"\n");
        sb.append("  # \n");
        sb.append("  # 默认: false（禁用）\n");
        sb.append("  enableReleaseCompliant: ").append(enableReleaseCompliant).append("\n\n");
        
        sb.append("  # 合规释放模式的替换字符\n");
        sb.append("  # 替换检测到的敏感词时使用的字符\n");
        sb.append("  # 默认: \"***\"\n");
        sb.append("  releaseCompliantReplacement: \"").append(escapeYamlString(releaseCompliantReplacement)).append("\"\n\n");
        
        sb.append("  # 是否忽略大小写进行匹配\n");
        sb.append("  # 开启后 \"ABC\" 和 \"abc\" 视为相同\n");
        sb.append("  # 默认: true\n");
        sb.append("  ignoreCase: ").append(ignoreCase).append("\n\n");
        
        sb.append("  # 过滤器检查优先级\n");
        
        sb.append("  # 控制各种过滤器的检查顺序\n");
        
        sb.append("  # 数字越小优先级越高（优先执行）\n");
        
        sb.append("  # 相同优先级的过滤器按列表顺序依次检查\n");
        
        sb.append("  # \n");
        
        sb.append("  # 优先级说明:\n");
        
        sb.append("  #   - 数字越小，检查顺序越靠前\n");
        
        sb.append("  #   - 相同数值的过滤器具有相同优先级，按配置顺序检查\n");
        
        sb.append("  #   - 例如：blacklist: 5 和 whitelist: 5 具有相同优先级\n");
        
        sb.append("  # \n");
        
        sb.append("  # 默认优先级顺序:\n");
        
        sb.append("  #   length (1) → conversions (2) → url (3) → whitelist (4) → blacklist (5) → wordBlacklist (6) → antiSpam (7)\n");
        
        sb.append("  # \n");
        
        sb.append("  # 示例配置（白名单和黑名单同级）:\n");
        
        sb.append("  #   filterPriority:\n");
        
        sb.append("  #     length: 1          # 优先级1（最高）\n");
        
        sb.append("  #     conversions: 2      # 优先级2\n");
        
        sb.append("  #     url: 3             # 优先级3\n");
        
        sb.append("  #     whitelist: 4       # 优先级4\n");
        
        sb.append("  #     blacklist: 4       # 优先级4（与白名单同级，先白名单后黑名单）\n");
        
        sb.append("  #     wordBlacklist: 5    # 优先级5\n");
        
        sb.append("  #     antiSpam: 6        # 优先级6（最低）\n");
        
        sb.append("  # \n");
        
        sb.append("  # 示例配置（黑名单优先于白名单）:\n");
        
        sb.append("  #   filterPriority:\n");
        
        sb.append("  #     length: 1\n");
        
        sb.append("  #     conversions: 2\n");
        
        sb.append("  #     url: 3\n");
        
        sb.append("  #     blacklist: 4       # 优先级4（优先）\n");
        
        sb.append("  #     whitelist: 5       # 优先级5（次之）\n");
        
        sb.append("  #     wordBlacklist: 6\n");
        
        sb.append("  #     antiSpam: 7\n");
        
        sb.append("  filterPriority:\n");
        
        this.appendIntMap(sb, filterPriority, "    ");
        
        sb.append("\n");        
        sb.append("  # 是否启用调试模式\n");        sb.append("  # 开启后会在控制台输出详细的过滤日志\n");
        sb.append("  # 用于排查过滤相关问题\n");
        sb.append("  # 默认: false\n");
        sb.append("  debugMode: ").append(debugMode).append("\n\n");
        
        // 白名单设置
        sb.append("# ==================== 白名单设置 ====================\n");
        sb.append("# 控制白名单功能的行为\n");
        sb.append("# ===========================================\n");
        sb.append("whitelistSettings:\n");
        sb.append("  # 是否启用白名单功能\n");
        sb.append("  # 关闭后白名单检查将被跳过\n");
        sb.append("  # 默认: true\n");
        sb.append("  enable: ").append(enableWhitelist).append("\n\n");
        
        // 黑名单设置
        sb.append("# ==================== 黑名单设置 ====================\n");
        sb.append("# 控制黑名单功能的行为\n");
        sb.append("# ===========================================\n");
        sb.append("blacklistSettings:\n");
        sb.append("  # 是否启用黑名单功能\n");
        sb.append("  # 默认: true\n");
        sb.append("  enable: ").append(enableBlacklist).append("\n\n");
        
        sb.append("  # 是否启用正则表达式匹配\n");
        sb.append("  # 开启后黑名单项会被视为正则表达式\n");
        sb.append("  # 警告: 正则匹配比普通匹配慢，需要了解正则语法\n");
        sb.append("  # 默认: false\n");
        sb.append("  enableRegex: ").append(enableRegex).append("\n\n");
        
        sb.append("  # 黑名单匹配模式\n");
        sb.append("  # - contains: 包含即匹配（消息包含黑名单词汇即屏蔽）\n");
        sb.append("  # - exact: 精确匹配（消息必须完全等于黑名单词汇才屏蔽）\n");
        sb.append("  # - startsWith: 前缀匹配（消息以黑名单词汇开头才屏蔽）\n");
        sb.append("  # - endsWith: 后缀匹配（消息以黑名单词汇结尾才屏蔽）\n");
        sb.append("  # 默认: contains\n");
        sb.append("  matchMode: \"").append(blacklistMatchMode).append("\"\n\n");
        
        // 单词黑名单设置
        sb.append("# ==================== 单词黑名单设置 ====================\n");
        sb.append("# 控制单词黑名单（刷屏屏蔽）功能的行为\n");
        sb.append("# ===========================================\n");
        sb.append("wordBlacklistSettings:\n");
        sb.append("  # 是否启用单词黑名单功能\n");
        sb.append("  # 默认: true\n");
        sb.append("  enable: ").append(enableWordBlacklist).append("\n\n");
        
        sb.append("  # 触发阈值（连续字符数）\n");
        sb.append("  # 消息中需要有多少个【连续】的黑名单字符才触发屏蔽\n");
        sb.append("  # 设为 1 表示只要有黑名单字符就屏蔽\n");
        sb.append("  # 设为 3 表示需要至少 3 个连续的黑名单字符才屏蔽\n");
        sb.append("  # 默认: 3\n");
        sb.append("  threshold: ").append(wordBlacklistThreshold).append("\n\n");
        
        // 夹杂词黑名单设置
        sb.append("# ==================== 夹杂词黑名单设置 ====================\n");
        sb.append("# 控制夹杂词黑名单（分隔符拆分检测）功能的行为\n");
        sb.append("# ===========================================\n");
        sb.append("mixedBlacklistSettings:\n");
        sb.append("  # 是否启用夹杂词黑名单功能\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   如果一句话内出现了某组内的所有字或词，就会直接屏蔽\n");
        sb.append("  #   用于防止一些人使用逗句号隔开被检测\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 防止用户用分隔符拆分敏感词，如：\"广，告\"（原本是\"广告\"）\n");
        sb.append("  #   - 防止用户用符号隔开敏感词，如：\"外.挂\"（原本是\"外挂\"）\n");
        sb.append("  #   - 防止用户用空格隔开敏感词，如：\"刷 屏\"（原本是\"刷屏\"）\n");
        sb.append("  # \n");
        sb.append("  # 默认: true\n");
        sb.append("  enable: ").append(enableMixedBlacklist).append("\n\n");
        
        sb.append("  # 触发阈值\n");
        sb.append("  # 消息中需要包含多少个组内的字/词才触发屏蔽\n");
        sb.append("  # 设为 0 或 1 表示只要有组内任意一个字/词就检查该组\n");
        sb.append("  # 设为组的大小表示必须包含组内所有字/词才触发屏蔽\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 设为组大小（默认）：必须包含组内所有字/词才屏蔽，更严格\n");
        sb.append("  #   - 设为组大小-1：缺少一个字/词也会触发，更宽松\n");
        sb.append("  # \n");
        sb.append("  # 默认: 0（自动使用组大小，即必须包含组内所有字/词）\n");
        sb.append("  threshold: ").append(mixedBlacklistThreshold).append("\n\n");
        
        sb.append("  # 忽略的字符（分隔符）\n");
        sb.append("  # 检测时忽略这些字符\n");
        sb.append("  # \n");
        sb.append("  # 示例配置:\n");
        sb.append("  #   mixedBlacklistIgnoreChars: \"，。、,. \"\n");
        sb.append("  # \n");
        sb.append("  # 默认: \"，。、,. \"（中文逗号、中文句号、中文顿号、英文逗号、英文句号、空格）\n");
        sb.append("  ignoreChars: \"").append(escapeYamlString(mixedBlacklistIgnoreChars)).append("\"\n\n");
        
        sb.append("  # 是否启用夹杂词乱序检测\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   开启后，即使夹杂词组内的字/词顺序打乱也能检测到\n");
        sb.append("  #   例如：组[\"广\", \"告\"]可以检测到\"告广\"、\"广告\"、\"告...广\"等\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 防止用户通过打乱字符顺序来绕过检测\n");
        sb.append("  # \n");
        sb.append("  # 示例:\n");
        sb.append("  #   - 关闭：组[\"广\", \"告\"] 只能检测到\"广...告\"（顺序一致）\n");
        sb.append("  #   - 开启：组[\"广\", \"告\"] 可以检测到\"告...广\"、\"广...告\"、\"广告\"等\n");
        sb.append("  # \n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  enableDisorderDetection: ").append(enableMixedDisorderDetection).append("\n\n");
        
        // 转换词设置
        sb.append("# ==================== 转换词设置 ====================\n");
        sb.append("# 控制词汇转换功能的行为\n");
        sb.append("# ===========================================\n");
        sb.append("conversionSettings:\n");
        sb.append("  # 是否启用转换词功能\n");
        sb.append("  # 默认: true\n");
        sb.append("  enable: ").append(enableConversions).append("\n\n");
        
        sb.append("  # 转换词匹配模式\n");
        sb.append("  # - contains: 包含即转换（默认，消息包含转换词就替换）\n");
        sb.append("  # - exact: 精确匹配（消息必须完全等于转换词才替换）\n");
        sb.append("  # - wholeWord: 整词匹配（只匹配完整的单词，避免误替换词组的一部分）\n");
        sb.append("  # \n");
        sb.append("  # 示例:\n");
        sb.append("  #   假设转换词: \"坏\" → \"好\"\n");
        sb.append("  #   - contains: \"坏蛋\" → \"好蛋\", \"好坏\" → \"好好\" (可能误替换)\n");
        sb.append("  #   - exact: \"坏\" → \"好\", \"坏蛋\" → \"坏蛋\" (不转换)\n");
        sb.append("  #   - wholeWord: \"坏蛋\" → \"好蛋\", \"好坏\" → \"好坏\" (只转换完整单词)\n");
        sb.append("  # 默认: contains\n");
        sb.append("  matchMode: \"").append(conversionMatchMode).append("\"\n\n");
        
        sb.append("  # 转换词触发阈值\n");
        sb.append("  # 消息中需要包含多少个转换词才执行转换\n");
        sb.append("  # 设为 0 表示只要有一个转换词就转换\n");
        sb.append("  # 设为 3 表示需要至少 3 个转换词才转换\n");
        sb.append("  # 用于防止少量误匹配导致整个消息被转换\n");
        sb.append("  # 默认: 1\n");
        sb.append("  threshold: ").append(conversionThreshold).append("\n\n");
        
        sb.append("  # 是否启用同类词转换功能\n");
        sb.append("  # 检测消息中是否主要由同一类字符组成，如果是则进行转换\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   1. 将消息中的字符分类（中文、英文、数字、特殊字符、刷屏字符等）\n");
        sb.append("  #   2. 统计各类字符的数量和比例\n");
        sb.append("  #   3. 如果某一类字符的比例超过阈值，则触发转换\n");
        sb.append("  #   4. 将整个消息替换为指定的内容\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 检测并转换纯刷屏消息（如\"啊啊啊啊\"、\"哈哈哈\"）\n");
        sb.append("  #   - 检测并转换纯数字刷屏（如\"123456\"）\n");
        sb.append("  #   - 检测并转换纯英文刷屏（如\"abcabc\"）\n");
        sb.append("  #   - 保留混合内容的正常消息（如\"你好啊123\"不会被转换）\n");
        sb.append("  # \n");
        sb.append("  # 示例:\n");
        sb.append("  #   - \"啊啊啊啊啊啊啊\" → 检测为中文刷屏类 → 转换为 \"...\"\n");
        sb.append("  #   - \"哈哈哈哈哈\" → 检测为中文刷屏类 → 转换为 \"...\"\n");
        sb.append("  #   - \"你好啊\" → 检测为中文混合类 → 不转换（通过）\n");
        sb.append("  #   - \"哈哈哈哈123\" → 检测为混合类 → 不转换（通过）\n");
        sb.append("  # 默认: true\n");
        sb.append("  enableSameClass: ").append(enableSameClassConversion).append("\n\n");
        
        sb.append("  # 同类词转换的字符分类配置\n");
        sb.append("  # 定义哪些字符属于同一类\n");
        sb.append("  # \n");
        sb.append("  # 分类说明:\n");
        sb.append("  #   - chinese_spam: 中文刷屏字符（如：啊、哈、呵、嘿）\n");
        sb.append("  #   - english_spam: 英文刷屏字符（如：a、b、c 重复）\n");
        sb.append("  #   - number: 数字字符（0-9）\n");
        sb.append("  #   - symbol: 特殊符号\n");
        sb.append("  #   - custom: 自定义分类\n");
        sb.append("  # \n");
        sb.append("  # 示例:\n");
        sb.append("  #   sameClassCategories:\n");
        sb.append("  #     chinese_spam:\n");
        sb.append("  #       - \"啊\"\n");
        sb.append("  #       - \"哈\"\n");
        sb.append("  #       - \"呵\"\n");
        sb.append("  #       - \"嘿\"\n");
        sb.append("  #     number:\n");
        sb.append("  #       - \"0\"\n");
        sb.append("  #       - \"1\"\n");
        sb.append("  #       - \"2\"\n");
        sb.append("  #       - \"3\"\n");
        sb.append("  #       - \"4\"\n");
        sb.append("  #       - \"5\"\n");
        sb.append("  #       - \"6\"\n");
        sb.append("  #       - \"7\"\n");
        sb.append("  #       - \"8\"\n");
        sb.append("  #       - \"9\"\n");
        sb.append("  sameClassCategories:\n");
        this.appendStringListMap(sb, sameClassCategories, "    ");
        sb.append("\n");
        
        sb.append("  # 同类词转换阈值（比例）\n");
        sb.append("  # 消息中同一类字符需要达到的比例才触发转换\n");
        sb.append("  # 范围: 0.0 到 1.0\n");
        sb.append("  # 0.8 表示 80% 的字符属于同一类时才转换\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 提高阈值以减少误判（如设为 0.9）\n");
        sb.append("  #   - 降低阈值以更严格地过滤刷屏（如设为 0.7）\n");
        sb.append("  # 默认: 0.8\n");
        sb.append("  sameClassThreshold: ").append(sameClassThreshold).append("\n\n");
        
        sb.append("  # 同类词转换的最小消息长度\n");
        sb.append("  # 消息至少需要有多少个字符才进行同类检测\n");
        sb.append("  # 短消息（如\"啊\"）不进行检测，避免误判\n");
        sb.append("  # 默认: 5\n");
        sb.append("  sameClassMinLength: ").append(sameClassMinLength).append("\n\n");
        
        sb.append("  # 同类词转换后的内容\n");
        sb.append("  # 当检测到消息主要由同类字符组成时，替换为此内容\n");
        sb.append("  # 支持颜色代码\n");
        sb.append("  # 默认: \"§7...\"\n");
        sb.append("  sameClassReplacement: \"").append(escapeYamlString(sameClassReplacement)).append("\"\n\n");
        
        sb.append("  # 同类词转换提示信息\n");
        sb.append("  # 当消息被同类词转换时显示的提示\n");
        sb.append("  # 默认: \"§e消息已优化显示\"\n");
        sb.append("  sameClassNotice: \"").append(escapeYamlString(sameClassConversionNotice)).append("\"\n\n");
        
        sb.append("  # ==================== 重复合并功能 ====================\n");
        sb.append("  # 检测消息中的重复项并自动合并为 \"内容(×数量)\" 格式\n");
        sb.append("  # ===========================================\n");
        sb.append("  \n");
        sb.append("  # 是否启用同类词合并功能\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   检测消息中的重复项并自动合并\n");
        sb.append("  #   支持以下重复模式:\n");
        sb.append("  #     - 重复单个词: \"好好好好好\" → \"好(×5)\"\n");
        sb.append("  #     - 重复词组: \"你好你好你好\" → \"你好(×3)\"\n");
        sb.append("  #     - 重复字符: \"aaaaa\" → \"a(×5)\"\n");
        sb.append("  #     - 重复短语: \"哈哈哈哈哈哈\" → \"哈哈(×4)\"\n");
        sb.append("  #     - 重复句式: \"你好啊123你好啊123\" → \"你好啊123(×2)\"\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 简化刷屏消息显示\n");
        sb.append("  #   - 保留消息内容的同时减少视觉干扰\n");
        sb.append("  #   - 区别于同类词转换（不改变内容，只改变格式）\n");
        sb.append("  # \n");
        sb.append("  # 示例:\n");
        sb.append("  #   - \"好好好好好\" → \"好(×5)\"\n");
        sb.append("  #   - \"啊啊啊啊啊\" → \"啊(×5)\"\n");
        sb.append("  #   - \"哈哈哈哈哈哈\" → \"哈哈(×4)\"\n");
        sb.append("  #   - \"你好你好你好\" → \"你好(×3)\"\n");
        sb.append("  #   - \"abcabcabc\" → \"abc(×3)\"\n");
        sb.append("  #   - \"你好世界\" → \"你好世界\"（不转换，无重复）\n");
        sb.append("  # \n");
        sb.append("  # 默认: true\n");
        sb.append("  enableRepeatMerge: ").append(enableRepeatMerge).append("\n\n");
        
        sb.append("  # 重复合并的最小重复次数\n");
        sb.append("  # 内容至少重复多少次才进行合并\n");
        sb.append("  # 示例: 设为 3 表示 \"好好好\" 会合并为 \"好(×3)\"，\"好好\" 不会合并\n");
        sb.append("  # 默认: 3\n");
        sb.append("  repeatMergeMinCount: ").append(repeatMergeMinCount).append("\n\n");
        
        sb.append("  # 重复合并的最大显示次数\n");
        sb.append("  # 超过此次数的重复不显示具体数量，显示为 \"+\"\n");
        sb.append("  # 示例: 设为 10，\"好×100\" 会显示为 \"好(×10+)\"\n");
        sb.append("  # 默认: 10\n");
        sb.append("  repeatMergeMaxDisplay: ").append(repeatMergeMaxDisplay).append("\n\n");
        
        sb.append("  # 重复合并的格式模板\n");
        sb.append("  # 可用占位符:\n");
        sb.append("  #   - {content}: 重复的内容\n");
        sb.append("  #   - {count}: 重复次数（正常显示）\n");
        sb.append("  #   - {countPlus}: 重复次数（超过最大值时）\n");
        sb.append("  # \n");
        sb.append("  # 示例配置:\n");
        sb.append("  #   - \"{content}(×{count})\" → \"好(×5)\" (默认)\n");
        sb.append("  #   - \"{content}[{count}]\" → \"好[5]\"\n");
        sb.append("  #   - \"{content}×{count}\" → \"好×5\"\n");
        sb.append("  #   - \"{content}\" → 只显示内容（隐藏次数）\n");
        sb.append("  # 默认: \"{content}(×{count})\"\n");
        sb.append("  repeatMergeFormat: \"").append(escapeYamlString(repeatMergeFormat)).append("\"\n\n");
        
        sb.append("  # 重复合并的超量格式模板\n");
        sb.append("  # 当重复次数超过 maxDisplay 时使用\n");
        sb.append("  # 可用占位符: {content} 和 {countPlus}\n");
        sb.append("  # 默认: \"{content}(×{countPlus}+)\"\n");
        sb.append("  repeatMergeOverflowFormat: \"").append(escapeYamlString(repeatMergeOverflowFormat)).append("\"\n\n");
        
        sb.append("  # 是否检测重复短语\n");
        sb.append("  # 是否检测多字符重复（如\"哈哈哈\"、\"你好你好\"）\n");
        sb.append("  # 关闭后只检测单字符重复\n");
        sb.append("  # 默认: true\n");
        sb.append("  enablePhraseDetection: ").append(enablePhraseRepeatDetection).append("\n\n");
        
        sb.append("  # 短语检测的最小长度\n");
        sb.append("  # 多少个字符以上才视为短语\n");
        sb.append("  # 示例: 设为 2 表示检测 \"哈哈\"、\"你好\" 等双字短语\n");
        sb.append("  # 默认: 2\n");
        sb.append("  phraseMinLength: ").append(phraseMinLength).append("\n\n");
        
        sb.append("  # 短语检测的最大长度\n");
        sb.append("  # 最多检测多长的短语\n");
        sb.append("  # 示例: 设为 5 表示最多检测 \"你好世界\" 这样的5字短语\n");
        sb.append("  # 默认: 10\n");
        sb.append("  phraseMaxLength: ").append(phraseMaxLength).append("\n\n");
        
        sb.append("  # 重复合并提示信息\n");
        sb.append("  # 默认: \"§e消息已合并显示\"\n");
        sb.append("  repeatMergeNotice: \"").append(escapeYamlString(repeatMergeNotice)).append("\"\n\n");
        
        sb.append("  # 转换后消息的格式\n");        sb.append("  # 可用占位符: {player} = 玩家名, {message} = 消息内容\n");
        sb.append("  # 支持颜色代码（使用 § 符号）\n");
        sb.append("  # 默认: \"§f<{player}> {message}\"\n");
        sb.append("  messageFormat: \"").append(escapeYamlString(convertedMessageFormat)).append("\"\n\n");
        
        sb.append("  # 是否在转换后显示提示\n");
        sb.append("  # 默认: false\n");
        sb.append("  showNotice: ").append(showConversionNotice).append("\n\n");
        
        sb.append("  # 转换提示信息\n");
        sb.append("  # 默认: \"§e[ChatPurity] 消息已自动修正\"\n");
        sb.append("  noticeMessage: \"").append(escapeYamlString(conversionNoticeMessage)).append("\"\n\n");

        // 屏蔽设置
        sb.append("# ==================== 屏蔽提示设置 ====================\n");
        sb.append("# 控制消息被屏蔽时的提示行为\n");
        sb.append("# ===========================================\n");
        sb.append("blockSettings:\n");
        sb.append("  # 是否在屏蔽消息时通知发送者\n");
        sb.append("  # 默认: true\n");
        sb.append("  notify: ").append(notifyBlocked).append("\n\n");

        sb.append("  # 屏蔽提示信息\n");
        sb.append("  # 支持颜色代码（使用 § 符号）\n");
        sb.append("  # 默认: \"§c[ChatPurity] 消息已被屏蔽\"\n");
        sb.append("  message: \"").append(escapeYamlString(blockedMessage)).append("\"\n\n");
        
        sb.append("  # 提示显示位置\n");
        sb.append("  # - action_bar: 在行动栏显示（推荐，不干扰聊天）\n");
        sb.append("  # - chat: 在聊天栏显示\n");
        sb.append("  # - title: 在屏幕中央显示标题\n");
        sb.append("  # 默认: action_bar\n");
        sb.append("  notifyPosition: \"").append(blockedNotifyPosition).append("\"\n\n");
        
        sb.append("  # 是否显示替换提示（合规释放模式）\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   在合规释放模式下，给玩家提示哪些词被替换了\n");
        sb.append("  #   让玩家知道消息被修改过\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 增加透明度，让玩家知道敏感词被替换\n");
        sb.append("  #   - 提醒玩家注意文明用语\n");
        sb.append("  # \n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  showReplacementNotice: ").append(showReplacementNotice).append("\n\n");
        
        sb.append("  # 替换提示信息\n");
        sb.append("  # 当消息中的敏感词被替换时显示的提示\n");
        sb.append("  # 支持颜色代码\n");
        sb.append("  # 默认: \"§e您的消息中包含敏感词，已被替换\"\n");
        sb.append("  replacementNoticeMessage: \"").append(escapeYamlString(replacementNoticeMessage)).append("\"\n\n");
        
        sb.append("  # 是否通知管理员严重违规\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   当检测到严重违规词时，通知在线管理员\n");
        sb.append("  #   方便管理员及时处理违规行为\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 需要管理员及时介入处理严重违规\n");
        sb.append("  #   - 监控高风险玩家行为\n");
        sb.append("  # \n");
        sb.append("  # 默认: false（禁用）\n");
        sb.append("  notifyAdmins: ").append(notifyAdmins).append("\n\n");
        
        sb.append("  # 需要通知管理员的敏感词列表\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   只有包含这些词的消息才会通知管理员\n");
        sb.append("  #   留空表示通知所有被屏蔽的消息\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 只关注严重的违规词\n");
        sb.append("  #   - 减少通知频率\n");
        sb.append("  # \n");
        sb.append("  # 示例配置:\n");
        sb.append("  #   - [] - 通知所有被屏蔽的消息\n");
        sb.append("  #   - [\"广告\", \"外挂\"] - 只通知包含\"广告\"或\"外挂\"的消息\n");
        sb.append("  # \n");
        sb.append("  # 默认: []（空列表，通知所有）\n");
        if (notifyWords.isEmpty()) {
            sb.append("  notifyWords: []\n");
        } else {
            appendList(sb, notifyWords, "  - \"");
        }
        sb.append("\n");
        
        sb.append("  # 管理员通知消息格式\n");
        sb.append("  # 可用占位符:\n");
        sb.append("  #   - {player}: 玩家名称\n");
        sb.append("  #   - {message}: 消息内容\n");
        sb.append("  #   - {reason}: 原因\n");
        sb.append("  # 默认: \"§c[ChatPurity] 玩家 {player} 发送了违规消息: {message} (原因: {reason})\"\n");
        sb.append("  adminNotifyMessage: \"").append(escapeYamlString(adminNotifyMessage)).append("\"\n\n");
        
        // 玩家警告机制设置
        sb.append("# ==================== 玩家警告机制设置 ====================\n");
        sb.append("# 控制玩家警告和惩罚机制\n");
        sb.append("# ===========================================\n");
        sb.append("warningSettings:\n");
        sb.append("  # 是否启用玩家警告机制\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   给玩家发送警告而不是直接屏蔽\n");
        sb.append("  #   达到警告次数后执行惩罚\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 给玩家改正机会\n");
        sb.append("  #   - 减少误杀\n");
        sb.append("  # \n");
        sb.append("  # 默认: false（禁用）\n");
        sb.append("  enable: ").append(enableWarning).append("\n\n");
        
        sb.append("  # 最大警告次数\n");
        sb.append("  # 玩家达到此警告次数后将被惩罚\n");
        sb.append("  # 默认: 3\n");
        sb.append("  maxWarnings: ").append(maxWarnings).append("\n\n");
        
        sb.append("  # 警告消息\n");
        sb.append("  # 可用占位符:\n");
        sb.append("  #   - {count}: 当前警告次数\n");
        sb.append("  #   - {max}: 最大警告次数\n");
        sb.append("  # 默认: \"§c[警告] 您的消息包含敏感词，请注意文明用语 ({count}/{max})\"\n");
        sb.append("  message: \"").append(escapeYamlString(warningMessage)).append("\"\n\n");
        
        sb.append("  # 达到警告次数后的惩罚类型\n");
        sb.append("  # - mute: 禁言\n");
        sb.append("  # - kick: 踢出服务器\n");
        sb.append("  # - tempban: 临时封禁\n");
        sb.append("  # 默认: \"mute\"\n");
        sb.append("  punishment: \"").append(warningPunishment).append("\"\n\n");
        
        // 防绕过检测设置
        sb.append("# ==================== 防绕过检测设置 ====================\n");
        sb.append("# 检测和防止常见的绕过方式\n");
        sb.append("# ===========================================\n");
        sb.append("antiBypassSettings:\n");
        sb.append("  # 是否启用防绕过检测\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   检测常见的绕过方式\n");
        sb.append("  #   包括颜色代码、Unicode变体、拼音混合、谐音字、同音字等\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 防止玩家通过各种方式绕过敏感词检测\n");
        sb.append("  # \n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  enable: ").append(enableAntiBypass).append("\n\n");
        
        sb.append("  # 是否检测颜色代码绕过\n");
        sb.append("  # 检测使用 Minecraft 颜色代码（§）的绕过尝试\n");
        sb.append("  # 例如: \"广§r告\"（使用颜色代码分隔）\n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  detectColorCodes: ").append(detectColorCodes).append("\n\n");
        
        sb.append("  # 是否检测 Unicode 变体绕过\n");
        sb.append("  # 检测使用相似字符替换的绕过尝试\n");
        sb.append("  # 例如: \"广吿\"（使用吿代替告）\n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  detectUnicodeVariants: ").append(detectUnicodeVariants).append("\n\n");
        
        sb.append("  # 是否检测拼音混合绕过\n");
        sb.append("  # 检测使用拼音和汉字混合的绕过尝试\n");
        sb.append("  # 例如: \"guang告\"、\"广gao\"\n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  detectPinyinMix: ").append(detectPinyinMix).append("\n\n");
        
        sb.append("  # 是否检测谐音字绕过\n");
        sb.append("  # 检测使用谐音字的绕过尝试\n");
        sb.append("  # 例如: \"光告\"（光是广的谐音）\n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  detectHomophones: ").append(detectHomophones).append("\n\n");
        
        sb.append("  # 谐音字映射表\n");
        sb.append("  # 格式: \"原字\": [\"谐音1\", \"谐音2\"]\n");
        sb.append("  # 示例:\n");
        sb.append("  #   \"广\": [\"光\", \"逛\"]\n");
        sb.append("  #   \"告\": [\"告\", \"搞\"]\n");
        if (homophoneMap.isEmpty()) {
            sb.append("  # 广: [\"光\", \"逛\"]\n");
            sb.append("  # 告: [\"搞\"]\n");
        } else {
            for (Map.Entry<String, List<String>> entry : homophoneMap.entrySet()) {
                sb.append("  \"").append(escapeYamlString(entry.getKey())).append("\": [");
                for (int i = 0; i < entry.getValue().size(); i++) {
                    sb.append("\"").append(escapeYamlString(entry.getValue().get(i))).append("\"");
                    if (i < entry.getValue().size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append("]\n");
            }
        }
        sb.append("\n");
        
        // 临时封禁设置
        sb.append("# ==================== 临时封禁设置 ====================\n");
        sb.append("# 控制临时封禁功能的行为\n");
        sb.append("# ===========================================\n");
        sb.append("tempBanSettings:\n");
        sb.append("  # 是否启用临时封禁功能\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   对频繁违规的玩家进行临时封禁\n");
        sb.append("  #   封禁时间可配置\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 处理严重违规行为\n");
        sb.append("  #   - 防止违规玩家继续破坏\n");
        sb.append("  # \n");
        sb.append("  # 默认: false（禁用）\n");
        sb.append("  enable: ").append(enableTempBan).append("\n\n");
        
        sb.append("  # 触发临时封禁的违规次数\n");
        sb.append("  # 玩家违规多少次后触发临时封禁\n");
        sb.append("  # 默认: 5\n");
        sb.append("  violations: ").append(tempBanViolations).append("\n\n");
        
        sb.append("  # 临时封禁时长\n");
        sb.append("  # 支持时间单位: s（秒）、m（分钟）、h（小时）\n");
        sb.append("  # 示例: \"30s\"（30秒）, \"5m\"（5分钟）, \"1h\"（1小时）\n");
        sb.append("  # 默认: \"30m\"\n");
        sb.append("  duration: \"").append(escapeYamlString(tempBanDuration)).append("\"\n\n");
        
        sb.append("  # 临时封禁消息\n");
        sb.append("  # 可用占位符:\n");
        sb.append("  #   - {duration}: 封禁时长\n");
        sb.append("  #   - {reason}: 封禁原因\n");
        sb.append("  # 默认: \"§c您已被临时封禁 {duration}，原因: {reason}\"\n");
        sb.append("  message: \"").append(escapeYamlString(tempBanMessage)).append("\"\n\n");
        
        // 权限设置
        sb.append("# ==================== 权限设置 ====================\n");
        sb.append("# 控制哪些玩家可以豁免过滤\n");
        sb.append("# ===========================================\n");
        sb.append("permissionSettings:\n");
        sb.append("  # 豁免过滤的权限等级\n");
        sb.append("  # 拥有此权限等级及以上的玩家豁免过滤\n");
        sb.append("  # 0 = 所有人被过滤, 4 = 管理员豁免\n");
        sb.append("  # 默认: 4\n");
        sb.append("  bypassLevel: ").append(bypassPermissionLevel).append("\n\n");
        
        sb.append("  # 豁免玩家列表（基于玩家名，不区分大小写）\n");
        sb.append("  # 用于豁免非 OP 玩家\n");
        sb.append("  bypassPlayers:\n");
        appendList(sb, bypassPlayers, "    - \"");
        sb.append("\n");
        
        // 命令过滤设置
        sb.append("# ==================== 命令过滤设置 ====================\n");
        sb.append("# 控制是否过滤通过命令发送的消息\n");
        sb.append("# ===========================================\n");
        sb.append("commandSettings:\n");
        sb.append("  # 是否过滤命令消息（如 /me、/say）\n");
        sb.append("  # 注意: 不包括 /msg、/tell 等私聊命令\n");
        sb.append("  # 默认: true\n");
        sb.append("  filterCommands: ").append(filterCommands).append("\n\n");
        
        sb.append("  # 需要过滤的命令列表\n");
        sb.append("  # 留空表示过滤所有命令消息（如果 filterCommands 为 true）\n");
        sb.append("  # 示例: [\"me\", \"say\", \"broadcast\"]\n");
        sb.append("  filteredCommands:\n");
        appendList(sb, filteredCommands, "    - \"");
        sb.append("\n");
        
        // 消息长度限制
        sb.append("# ==================== 消息长度限制 ====================\n");
        sb.append("# 控制消息的最大长度\n");
        sb.append("# ===========================================\n");
        sb.append("lengthSettings:\n");
        sb.append("  # 是否启用消息长度限制\n");
        sb.append("  # 默认: false\n");
        sb.append("  enable: ").append(enableLengthLimit).append("\n\n");
        
        sb.append("  # 消息最大长度（字符数）\n");
        sb.append("  # 默认: 256\n");
        sb.append("  maxLength: ").append(maxMessageLength).append("\n\n");
        
        sb.append("  # 消息过长时的提示信息\n");
        sb.append("  # 可用占位符: {max} = 最大长度, {current} = 当前长度\n");
        sb.append("  # 默认: \"§c消息过长，最多允许 {max} 个字符\"\n");
        sb.append("  message: \"").append(escapeYamlString(lengthLimitMessage)).append("\"\n\n");
        
        // 防刷屏设置
        sb.append("# ==================== 防刷屏设置 ====================\n");
        sb.append("# 防止玩家快速发送重复消息\n");
        sb.append("# ===========================================\n");
        sb.append("spamSettings:\n");
        sb.append("  # 是否启用防刷屏功能\n");
        sb.append("  # 默认: false\n");
        sb.append("  enable: ").append(enableAntiSpam).append("\n\n");
        
        sb.append("  # 相同消息冷却时间（秒）\n");
        sb.append("  # 玩家发送相同消息后需要等待的时间\n");
        sb.append("  # 设为 0 表示不允许发送重复消息\n");
        sb.append("  # 默认: 5\n");
        sb.append("  cooldownSeconds: ").append(spamCooldownSeconds).append("\n\n");
        
        sb.append("  # 防刷屏模式\n");
        sb.append("  # - same: 相同消息冷却（发送相同消息后需要等待）\n");
        sb.append("  # - fast: 快速消息限制（短时间内发送过多消息）\n");
        sb.append("  # - both: 同时启用相同消息冷却和快速消息限制\n");
        sb.append("  # 默认: same\n");
        sb.append("  mode: \"").append(antiSpamMode).append("\"\n\n");
        
        sb.append("  # 相同消息冷却时间（秒）\n");
        sb.append("  # 玩家发送相同消息后需要等待的时间\n");
        sb.append("  # 设为 0 表示不允许发送重复消息\n");
        sb.append("  # 默认: 5\n");
        sb.append("  cooldownSeconds: ").append(spamCooldownSeconds).append("\n\n");
        
        sb.append("  # 快速消息限制 - 时间窗口（秒）\n");
        sb.append("  # 在此时间窗口内发送的消息数量不能超过 maxMessages\n");
        sb.append("  # 仅在 mode 为 fast 或 both 时生效\n");
        sb.append("  # 默认: 10\n");
        sb.append("  timeWindow: ").append(spamTimeWindow).append("\n\n");
        
        sb.append("  # 快速消息限制 - 最大消息数\n");
        sb.append("  # 在时间窗口内最多允许发送的消息数量\n");
        sb.append("  # 仅在 mode 为 fast 或 both 时生效\n");
        sb.append("  # 默认: 5\n");
        sb.append("  maxMessages: ").append(spamMaxMessages).append("\n\n");
        
        sb.append("  # 刷屏惩罚时间（秒）\n");
        sb.append("  # 触发刷屏限制后的禁言时间\n");
        sb.append("  # 设为 0 表示只阻止当前消息，不禁言\n");
        sb.append("  # 默认: 0\n");
        sb.append("  punishmentTime: ").append(spamPunishmentTime).append("\n\n");
        
        sb.append("  # 刷屏提示信息\n");
        sb.append("  # 可用占位符: {seconds} = 剩余等待秒数\n");
        sb.append("  # 默认: \"§c请勿刷屏，请等待 {seconds} 秒后再试\"\n");
        sb.append("  message: \"").append(escapeYamlString(spamMessage)).append("\"\n\n");
        
        sb.append("  # 刷屏惩罚提示信息\n");
        sb.append("  # 可用占位符: {time} = 禁言时长\n");
        sb.append("  # 默认: \"§c您已被禁言 {time} 秒，请遵守聊天规则\"\n");
        sb.append("  punishmentMessage: \"").append(escapeYamlString(spamPunishmentMessage)).append("\"\n\n");
        
        // 日志设置
        sb.append("# ==================== 日志记录设置 ====================\n");
        sb.append("# 控制违规消息的日志记录\n");
        sb.append("# ===========================================\n");
        sb.append("logSettings:\n");
        sb.append("  # 是否启用日志记录\n");
        sb.append("  # \n");
        sb.append("  # 功能说明:\n");
        sb.append("  #   记录所有被屏蔽或替换的消息\n");
        sb.append("  #   方便后续分析违规情况\n");
        sb.append("  # \n");
        sb.append("  # 使用场景:\n");
        sb.append("  #   - 统计违规词频率\n");
        sb.append("  #   - 分析违规玩家行为\n");
        sb.append("  #   - 生成违规报告\n");
        sb.append("  # \n");
        sb.append("  # 默认: false（禁用）\n");
        sb.append("  enable: ").append(enableLog).append("\n\n");
        
        sb.append("  # 日志文件路径\n");
        sb.append("  # 指定日志文件的保存路径（相对于服务器目录）\n");
        sb.append("  # 默认: \"chatpurity_filtered.log\"\n");
        sb.append("  path: \"").append(escapeYamlString(logPath)).append("\"\n\n");
        
        sb.append("  # 日志中是否记录玩家名称\n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  logPlayerName: ").append(logPlayerName).append("\n\n");
        
        sb.append("  # 日志中是否记录时间戳\n");
        sb.append("  # 默认: true（启用）\n");
        sb.append("  logTimestamp: ").append(logTimestamp).append("\n\n");
        
        sb.append("  # 日志格式\n");
        sb.append("  # 可用占位符:\n");
        sb.append("  #   - {timestamp}: 时间戳\n");
        sb.append("  #   - {player}: 玩家名称\n");
        sb.append("  #   - {message}: 原始消息\n");
        sb.append("  #   - {type}: 操作类型（blocked/replaced）\n");
        sb.append("  #   - {reason}: 原因（blacklist/wordblacklist/mixedblacklist等）\n");
        sb.append("  # 默认: \"[{timestamp}] {player} - {type}: {message} (原因: {reason})\"\n");
        sb.append("  format: \"").append(escapeYamlString(logFormat)).append("\"\n\n");
        
        // URL 设置        sb.append("# ==================== URL/链接屏蔽设置 ====================\n");
        sb.append("# 自动检测并屏蔽包含网址的消息\n");
        sb.append("# ===========================================\n");
        sb.append("urlSettings:\n");
        sb.append("  # 是否屏蔽包含 URL 的消息\n");
        sb.append("  # 自动检测常见的 URL 格式\n");
        sb.append("  # 默认: false\n");
        sb.append("  blockUrls: ").append(blockUrls).append("\n\n");
        
        sb.append("  # URL 屏蔽提示信息\n");
        sb.append("  # 默认: \"§c不允许发送链接\"\n");
        sb.append("  blockedMessage: \"").append(escapeYamlString(urlBlockedMessage)).append("\"\n\n");
        
        sb.append("  # URL 白名单域名\n");
        sb.append("  # 只有这些域名的链接可以发送\n");
        sb.append("  # 示例: [\"minecraft.net\", \"mojang.com\"]\n");
        sb.append("  whitelist:\n");
        appendList(sb, urlWhitelist, "    - \"");
        sb.append("\n");
        
        // 建议设置
        sb.append("# ==================== 建议提示设置 ====================\n");
        sb.append("# 当消息被屏蔽时显示修改建议\n");
        sb.append("# ===========================================\n");
        sb.append("suggestionSettings:\n");
        sb.append("  # 是否向被屏蔽的玩家显示建议\n");
        sb.append("  # 默认: false\n");
        sb.append("  enable: ").append(showSuggestions).append("\n\n");
        
        sb.append("  # 建议提示信息\n");
        sb.append("  # 默认: \"§e建议修改消息中的敏感词汇后重试\"\n");
        sb.append("  message: \"").append(escapeYamlString(suggestionMessage)).append("\"");
        
        return sb.toString();
    }
    
    // ==================== 辅助方法 ====================
    
    private Yaml createYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options);
    }
    
    private void appendList(StringBuilder sb, List<String> list, String prefix) {
        if (list.isEmpty()) {
            sb.append(prefix.replace("- \"", "[]"));
        } else {
            for (String item : list) {
                sb.append(prefix).append(escapeYamlString(item)).append("\"\n");
            }
        }
    }
    
    private void appendMap(StringBuilder sb, Map<String, String> map, String prefix) {
        if (map.isEmpty()) {
            sb.append("  {}");
        } else {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                sb.append(prefix).append(escapeYamlString(entry.getKey()))
                  .append("\": \"").append(escapeYamlString(entry.getValue())).append("\"\n");
            }
        }
    }
    
    private void appendIntMap(StringBuilder sb, Map<String, Integer> map, String indent) {
        if (map.isEmpty()) {
            sb.append("  {}");
        } else {
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                sb.append(indent).append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
    }
    
    private void appendStringListMap(StringBuilder sb, Map<String, List<String>> map, String indent) {
        if (map.isEmpty()) {
            sb.append("  {}");
        } else {
            for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                sb.append(indent).append(entry.getKey()).append(":\n");
                for (String item : entry.getValue()) {
                    sb.append(indent).append("  - \"").append(escapeYamlString(item)).append("\"\n");
                }
            }
        }
    }
    
    private String escapeYamlString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    
    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
    
    @SuppressWarnings("unchecked")
    private List<List<String>> getMixedBlacklistGroups(Map<String, Object> data) {
        Object value = data.get("mixedBlacklistGroups");
        if (value instanceof List) {
            List<List<String>> result = new ArrayList<>();
            for (Object group : (List<?>) value) {
                if (group instanceof List) {
                    List<String> groupList = new ArrayList<>();
                    for (Object item : (List<?>) group) {
                        if (item != null) {
                            groupList.add(item.toString());
                        }
                    }
                    if (!groupList.isEmpty()) {
                        result.add(groupList);
                    }
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, String> getStringMap(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Map) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }
    
    /**
     * 获取 String -> String 映射（用于拼音映射和 Unicode 变体映射）
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getStringToStringMap(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Map) {
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
            return result;
        }
        return new HashMap<>();
    }
    
    /**
     * 初始化默认的拼音到汉字映射
     */
    private void initDefaultPinyinMap() {
        pinyinToCharMap.put("guang", "广");
        pinyinToCharMap.put("gao", "告");
        pinyinToCharMap.put("wai", "外");
        pinyinToCharMap.put("gua", "挂");
        pinyinToCharMap.put("shua", "刷");
        pinyinToCharMap.put("ping", "屏");
        pinyinToCharMap.put("gu", "孤");
        pinyinToCharMap.put("kai", "开");
        pinyinToCharMap.put("jia", "假");
        pinyinToCharMap.put("mai", "卖");
        pinyinToCharMap.put("qiang", "强");
        pinyinToCharMap.put("dao", "盗");
        pinyinToCharMap.put("che", "车");
        pinyinToCharMap.put("pian", "骗");
        pinyinToCharMap.put("zha", "诈");
    }
    
    /**
     * 初始化默认的 Unicode 变体映射
     * 包含常见的 CJK 兼容字符和相似字形
     */
    private void initDefaultUnicodeVariantMap() {
        // 常见敏感词变体
        unicodeVariantMap.put("吿", "告");  // U+543F
        unicodeVariantMap.put("哊", "有");  // U+54CA
        unicodeVariantMap.put("哕", "有");  // U+54D5
        unicodeVariantMap.put("哃", "同");  // U+54C3
        unicodeVariantMap.put("哅", "好");  // U+54C5
        
        // 广告相关变体
        unicodeVariantMap.put("広", "广");  // 日文汉字
        unicodeVariantMap.put("廣", "广");  // 繁体
        unicodeVariantMap.put("吿", "告");  // 变体
        
        // 外挂相关变体
        unicodeVariantMap.put("掛", "挂");  // 繁体
        unicodeVariantMap.put("罣", "挂");  // 异体
        
        // 刷屏相关变体
        unicodeVariantMap.put("屏", "屏");
        
        // 数字和字母变体（全角字符）
        unicodeVariantMap.put("０", "0");
        unicodeVariantMap.put("１", "1");
        unicodeVariantMap.put("２", "2");
        unicodeVariantMap.put("３", "3");
        unicodeVariantMap.put("４", "4");
        unicodeVariantMap.put("５", "5");
        unicodeVariantMap.put("６", "6");
        unicodeVariantMap.put("７", "7");
        unicodeVariantMap.put("８", "8");
        unicodeVariantMap.put("９", "9");
        unicodeVariantMap.put("ａ", "a");
        unicodeVariantMap.put("ｂ", "b");
        unicodeVariantMap.put("ｃ", "c");
        unicodeVariantMap.put("ｄ", "d");
        unicodeVariantMap.put("ｅ", "e");
        unicodeVariantMap.put("ｆ", "f");
        unicodeVariantMap.put("ｇ", "g");
        unicodeVariantMap.put("ｈ", "h");
        unicodeVariantMap.put("ｉ", "i");
        unicodeVariantMap.put("ｊ", "j");
        unicodeVariantMap.put("ｋ", "k");
        unicodeVariantMap.put("ｌ", "l");
        unicodeVariantMap.put("ｍ", "m");
        unicodeVariantMap.put("ｎ", "n");
        unicodeVariantMap.put("ｏ", "o");
        unicodeVariantMap.put("ｐ", "p");
        unicodeVariantMap.put("ｑ", "q");
        unicodeVariantMap.put("ｒ", "r");
        unicodeVariantMap.put("ｓ", "s");
        unicodeVariantMap.put("ｔ", "t");
        unicodeVariantMap.put("ｕ", "u");
        unicodeVariantMap.put("ｖ", "v");
        unicodeVariantMap.put("ｗ", "w");
        unicodeVariantMap.put("ｘ", "x");
        unicodeVariantMap.put("ｙ", "y");
        unicodeVariantMap.put("ｚ", "z");
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Integer> getIntMap(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Map) {
            Map<String, Integer> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey().toString(), ((Number) entry.getValue()).intValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> getStringListMap(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Map) {
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null && entry.getValue() instanceof List) {
                    List<String> list = new ArrayList<>();
                    for (Object item : (List<?>) entry.getValue()) {
                        if (item != null) {
                            list.add(item.toString());
                        }
                    }
                    result.put(entry.getKey().toString(), list);
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }
    
    private double getDouble(Map<String, Object> data, String key, double defaultValue) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }
    
    private boolean getBoolean(Map<String, Object> data, String key, boolean defaultValue) {
        Object value = data.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }
    
    private String getString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        if (value != null) {
            return value.toString();
        }
        return defaultValue;
    }
    
    private int getInt(Map<String, Object> data, String key, int defaultValue) {
        Object value = data.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
    
    // ==================== Getter 方法 ====================
    
    public List<String> getImports() { return imports; }
    public Path getConfigDir() { return configDir; }
    
    public List<String> getWhitelist() { return whitelist; }
    public List<String> getBlacklist() { return blacklist; }
    public List<String> getWordBlacklist() { return wordBlacklist; }
    public Map<String, String> getConversions() { return conversions; }
    public Map<String, String> getCustomReplacements() { return customReplacements; }
    
    public boolean isEnableFilter() { return enableFilter; }
    public boolean isIgnoreCase() { return ignoreCase; }
    public Map<String, Integer> getFilterPriority() { return filterPriority; }
    public boolean isDebugMode() { return debugMode; }
    public boolean isEnableReleaseCompliant() { return enableReleaseCompliant; }
    public String getReleaseCompliantReplacement() { return releaseCompliantReplacement; }
    
    public boolean isEnableWhitelist() { return enableWhitelist; }
    public boolean isEnableBlacklist() { return enableBlacklist; }
    public boolean isEnableRegex() { return enableRegex; }
    public String getBlacklistMatchMode() { return blacklistMatchMode; }
    
    public boolean isEnableWordBlacklist() { return enableWordBlacklist; }
    public int getWordBlacklistThreshold() { return wordBlacklistThreshold; }
    
    public List<List<String>> getMixedBlacklistGroups() { return mixedBlacklistGroups; }
    public boolean isEnableMixedBlacklist() { return enableMixedBlacklist; }
    public int getMixedBlacklistThreshold() { return mixedBlacklistThreshold; }
    public String getMixedBlacklistIgnoreChars() { return mixedBlacklistIgnoreChars; }
    public boolean isEnableMixedDisorderDetection() { return enableMixedDisorderDetection; }
    
    public boolean isEnableConversions() { return enableConversions; }
    public String getConversionMatchMode() { return conversionMatchMode; }
    public int getConversionThreshold() { return conversionThreshold; }
    public boolean isEnableSameClassConversion() { return enableSameClassConversion; }
    public Map<String, List<String>> getSameClassCategories() { return sameClassCategories; }
    public double getSameClassThreshold() { return sameClassThreshold; }
    public int getSameClassMinLength() { return sameClassMinLength; }
    public String getSameClassReplacement() { return sameClassReplacement; }
    public String getSameClassConversionNotice() { return sameClassConversionNotice; }
    public boolean isEnableRepeatMerge() { return enableRepeatMerge; }
    public int getRepeatMergeMinCount() { return repeatMergeMinCount; }
    public int getRepeatMergeMaxDisplay() { return repeatMergeMaxDisplay; }
    public String getRepeatMergeFormat() { return repeatMergeFormat; }
    public String getRepeatMergeOverflowFormat() { return repeatMergeOverflowFormat; }
    public boolean isEnablePhraseRepeatDetection() { return enablePhraseRepeatDetection; }
    public int getPhraseMinLength() { return phraseMinLength; }
    public int getPhraseMaxLength() { return phraseMaxLength; }
    public String getRepeatMergeNotice() { return repeatMergeNotice; }
    public String getConvertedMessageFormat() { return convertedMessageFormat; }
    public boolean isShowConversionNotice() { return showConversionNotice; }
    public String getConversionNoticeMessage() { return conversionNoticeMessage; }
    
    public boolean isNotifyBlocked() { return notifyBlocked; }
    public String getBlockedMessage() { return blockedMessage; }
    public boolean isShowReplacementNotice() { return showReplacementNotice; }
    public String getReplacementNoticeMessage() { return replacementNoticeMessage; }
    public String getBlockedNotifyPosition() { return blockedNotifyPosition; }
    public boolean isNotifyAdmins() { return notifyAdmins; }
    public List<String> getNotifyWords() { return notifyWords; }
    public String getAdminNotifyMessage() { return adminNotifyMessage; }
    public boolean isEnableWarning() { return enableWarning; }
    public int getMaxWarnings() { return maxWarnings; }
    public String getWarningMessage() { return warningMessage; }
    public String getWarningPunishment() { return warningPunishment; }
    public boolean isEnableAntiBypass() { return enableAntiBypass; }
    public boolean isDetectColorCodes() { return detectColorCodes; }
    public boolean isDetectUnicodeVariants() { return detectUnicodeVariants; }
    public boolean isDetectPinyinMix() { return detectPinyinMix; }
    public boolean isDetectHomophones() { return detectHomophones; }
    public Map<String, List<String>> getHomophoneMap() { return homophoneMap; }
    public List<String> getPinyinDetectList() { return pinyinDetectList; }
    public Map<String, String> getPinyinToCharMap() { return pinyinToCharMap; }
    public Map<String, String> getUnicodeVariantMap() { return unicodeVariantMap; }
    public boolean isEnableTempBan() { return enableTempBan; }
    public int getTempBanViolations() { return tempBanViolations; }
    public String getTempBanDuration() { return tempBanDuration; }
    public String getTempBanMessage() { return tempBanMessage; }
    
    public boolean isEnableLog() { return enableLog; }
    public String getLogPath() { return logPath; }
    public boolean isLogPlayerName() { return logPlayerName; }
    public boolean isLogTimestamp() { return logTimestamp; }
    public String getLogFormat() { return logFormat; }
    
    public int getBypassPermissionLevel() { return bypassPermissionLevel; }
    public List<String> getBypassPlayers() { return bypassPlayers; }
    
    public boolean isFilterCommands() { return filterCommands; }
    public List<String> getFilteredCommands() { return filteredCommands; }
    
    public boolean isEnableLengthLimit() { return enableLengthLimit; }
    public int getMaxMessageLength() { return maxMessageLength; }
    public String getLengthLimitMessage() { return lengthLimitMessage; }
    
    public boolean isEnableAntiSpam() { return enableAntiSpam; }
    public String getAntiSpamMode() { return antiSpamMode; }
    public int getSpamCooldownSeconds() { return spamCooldownSeconds; }
    public int getSpamTimeWindow() { return spamTimeWindow; }
    public int getSpamMaxMessages() { return spamMaxMessages; }
    public int getSpamPunishmentTime() { return spamPunishmentTime; }
    public String getSpamMessage() { return spamMessage; }
    public String getSpamPunishmentMessage() { return spamPunishmentMessage; }
    
    public boolean isBlockUrls() { return blockUrls; }
    public String getUrlBlockedMessage() { return urlBlockedMessage; }
    public List<String> getUrlWhitelist() { return urlWhitelist; }
    
    public boolean isShowSuggestions() { return showSuggestions; }
    public String getSuggestionMessage() { return suggestionMessage; }
    
    // ==================== 列表操作方法 ====================
    
    public void addToWhitelist(String word) {
        if (!whitelist.contains(word)) {
            whitelist.add(word);
            save();
        }
    }
    
    public void removeFromWhitelist(String word) {
        whitelist.remove(word);
        save();
    }
    
    public void addToBlacklist(String word) {
        if (!blacklist.contains(word)) {
            blacklist.add(word);
            save();
        }
    }
    
    public void removeFromBlacklist(String word) {
        blacklist.remove(word);
        save();
    }
    
    public void addToWordBlacklist(String word) {
        if (!wordBlacklist.contains(word)) {
            wordBlacklist.add(word);
            save();
        }
    }
    
    public void removeFromWordBlacklist(String word) {
        wordBlacklist.remove(word);
        save();
    }
    
    public void addToMixedBlacklistGroup(List<String> group) {
        if (!mixedBlacklistGroups.contains(group)) {
            mixedBlacklistGroups.add(group);
            save();
        }
    }
    
    public void removeFromMixedBlacklistGroup(int index) {
        if (index >= 0 && index < mixedBlacklistGroups.size()) {
            mixedBlacklistGroups.remove(index);
            save();
        }
    }
    
    public void clearMixedBlacklistGroups() {
        mixedBlacklistGroups.clear();
        save();
    }
    
    public void setEnableMixedBlacklist(boolean enabled) {
        enableMixedBlacklist = enabled;
        save();
    }
    
    public void setMixedBlacklistThreshold(int threshold) {
        mixedBlacklistThreshold = threshold;
        save();
    }
    
    public void setMixedBlacklistIgnoreChars(String chars) {
        mixedBlacklistIgnoreChars = chars;
        save();
    }
    
    public void addConversion(String from, String to) {
        conversions.put(from, to);
        save();
    }
    
    public void removeConversion(String from) {
        conversions.remove(from);
        save();
    }
    
    // ==================== Setter 方法 ====================
    
    public void setImports(List<String> value) { this.imports = value; save(); }
    public void setEnableFilter(boolean value) { this.enableFilter = value; save(); }
    public void setIgnoreCase(boolean value) { this.ignoreCase = value; save(); }
    public void setDebugMode(boolean value) { this.debugMode = value; save(); }
    public void setEnableReleaseCompliant(boolean value) { this.enableReleaseCompliant = value; save(); }
    public void setReleaseCompliantReplacement(String value) { this.releaseCompliantReplacement = value; save(); }
    public void setEnableWhitelist(boolean value) { this.enableWhitelist = value; save(); }
    public void setEnableBlacklist(boolean value) { this.enableBlacklist = value; save(); }
    public void setEnableRegex(boolean value) { this.enableRegex = value; save(); }
    public void setBlacklistMatchMode(String value) { this.blacklistMatchMode = value; save(); }
    public void setEnableWordBlacklist(boolean value) { this.enableWordBlacklist = value; save(); }
    public void setWordBlacklistThreshold(int value) { this.wordBlacklistThreshold = value; save(); }
    public void setEnableMixedDisorderDetection(boolean value) { this.enableMixedDisorderDetection = value; save(); }
    public void setEnableConversions(boolean value) { this.enableConversions = value; save(); }
    public void setConversionMatchMode(String value) { this.conversionMatchMode = value; save(); }
    public void setConversionThreshold(int value) { this.conversionThreshold = value; save(); }
    public void setEnableSameClassConversion(boolean value) { this.enableSameClassConversion = value; save(); }
    public void setSameClassThreshold(double value) { this.sameClassThreshold = value; save(); }
    public void setSameClassMinLength(int value) { this.sameClassMinLength = value; save(); }
    public void setSameClassReplacement(String value) { this.sameClassReplacement = value; save(); }
    public void setSameClassConversionNotice(String value) { this.sameClassConversionNotice = value; save(); }
    public void setEnableRepeatMerge(boolean value) { this.enableRepeatMerge = value; save(); }
    public void setRepeatMergeMinCount(int value) { this.repeatMergeMinCount = value; save(); }
    public void setRepeatMergeMaxDisplay(int value) { this.repeatMergeMaxDisplay = value; save(); }
    public void setRepeatMergeFormat(String value) { this.repeatMergeFormat = value; save(); }
    public void setRepeatMergeOverflowFormat(String value) { this.repeatMergeOverflowFormat = value; save(); }
    public void setEnablePhraseRepeatDetection(boolean value) { this.enablePhraseRepeatDetection = value; save(); }
    public void setPhraseMinLength(int value) { this.phraseMinLength = value; save(); }
    public void setPhraseMaxLength(int value) { this.phraseMaxLength = value; save(); }
    public void setRepeatMergeNotice(String value) { this.repeatMergeNotice = value; save(); }
    public void setConvertedMessageFormat(String value) { this.convertedMessageFormat = value; save(); }
    public void setShowConversionNotice(boolean value) { this.showConversionNotice = value; save(); }
    public void setConversionNoticeMessage(String value) { this.conversionNoticeMessage = value; save(); }
    public void setNotifyBlocked(boolean value) { this.notifyBlocked = value; save(); }
    public void setBlockedMessage(String value) { this.blockedMessage = value; save(); }
    public void setShowReplacementNotice(boolean value) { this.showReplacementNotice = value; save(); }
    public void setReplacementNoticeMessage(String value) { this.replacementNoticeMessage = value; save(); }
    public void setBlockedNotifyPosition(String value) { this.blockedNotifyPosition = value; save(); }
    public void setNotifyAdmins(boolean value) { this.notifyAdmins = value; save(); }
    public void setAdminNotifyMessage(String value) { this.adminNotifyMessage = value; save(); }
    public void setEnableWarning(boolean value) { this.enableWarning = value; save(); }
    public void setMaxWarnings(int value) { this.maxWarnings = value; save(); }
    public void setWarningMessage(String value) { this.warningMessage = value; save(); }
    public void setWarningPunishment(String value) { this.warningPunishment = value; save(); }
    public void setEnableAntiBypass(boolean value) { this.enableAntiBypass = value; save(); }
    public void setDetectColorCodes(boolean value) { this.detectColorCodes = value; save(); }
    public void setDetectUnicodeVariants(boolean value) { this.detectUnicodeVariants = value; save(); }
    public void setDetectPinyinMix(boolean value) { this.detectPinyinMix = value; save(); }
    public void setDetectHomophones(boolean value) { this.detectHomophones = value; save(); }
    public void setEnableTempBan(boolean value) { this.enableTempBan = value; save(); }
    public void setTempBanViolations(int value) { this.tempBanViolations = value; save(); }
    public void setTempBanDuration(String value) { this.tempBanDuration = value; save(); }
    public void setTempBanMessage(String value) { this.tempBanMessage = value; save(); }
    public void setEnableLog(boolean value) { this.enableLog = value; save(); }
    public void setLogPath(String value) { this.logPath = value; save(); }
    public void setLogPlayerName(boolean value) { this.logPlayerName = value; save(); }
    public void setLogTimestamp(boolean value) { this.logTimestamp = value; save(); }
    public void setLogFormat(String value) { this.logFormat = value; save(); }
    public void setBypassPermissionLevel(int value) { this.bypassPermissionLevel = value; save(); }
    public void setFilterCommands(boolean value) { this.filterCommands = value; save(); }
    public void setEnableLengthLimit(boolean value) { this.enableLengthLimit = value; save(); }
    public void setMaxMessageLength(int value) { this.maxMessageLength = value; save(); }
    public void setLengthLimitMessage(String value) { this.lengthLimitMessage = value; save(); }
    public void setEnableAntiSpam(boolean value) { this.enableAntiSpam = value; save(); }
    public void setAntiSpamMode(String value) { this.antiSpamMode = value; save(); }
    public void setSpamCooldownSeconds(int value) { this.spamCooldownSeconds = value; save(); }
    public void setSpamTimeWindow(int value) { this.spamTimeWindow = value; save(); }
    public void setSpamMaxMessages(int value) { this.spamMaxMessages = value; save(); }
    public void setSpamPunishmentTime(int value) { this.spamPunishmentTime = value; save(); }
    public void setSpamMessage(String value) { this.spamMessage = value; save(); }
    public void setSpamPunishmentMessage(String value) { this.spamPunishmentMessage = value; save(); }
    public void setBlockUrls(boolean value) { this.blockUrls = value; save(); }
    public void setUrlBlockedMessage(String value) { this.urlBlockedMessage = value; save(); }
    public void setShowSuggestions(boolean value) { this.showSuggestions = value; save(); }
    public void setSuggestionMessage(String value) { this.suggestionMessage = value; save(); }
}