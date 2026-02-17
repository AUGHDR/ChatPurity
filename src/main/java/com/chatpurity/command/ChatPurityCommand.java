package com.chatpurity.command;

import com.chatpurity.ChatPurityMod;
import com.chatpurity.handler.WarningHandler;
import com.chatpurity.handler.TempBanHandler;
import com.chatpurity.handler.AntiSpamHandler;
import com.chatpurity.util.ChatPurityUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * ChatPurity 命令处理器
 * 
 * <p>注册和管理 /chatpurity 命令及其子命令，提供以下功能：
 * <ul>
 *   <li>配置管理：reload, list</li>
 *   <li>白名单管理：whitelist add/remove/list</li>
 *   <li>黑名单管理：blacklist add/remove/list</li>
 *   <li>单词黑名单管理：wordblacklist add/remove/list</li>
 *   <li>转换词管理：conversion add/remove/list</li>
 *   <li>禁言管理：mute/unmute/mutelist</li>
 *   <li>封禁管理：unban</li>
 *   <li>设置管理：set [分类] [选项] [值]</li>
 * </ul>
 * 
 * <p>所有命令需要 OP 权限（等级 4）
 */
public class ChatPurityCommand {

    /** 执行命令所需的 OP 等级 */
    private static final int OP_LEVEL = 4;

    /**
     * 注册命令到调度器
     * @param dispatcher 命令调度器
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // 主命令 /chatpurity
        var root = literal("chatpurity")
            .requires(source -> source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.fromLevel(OP_LEVEL))));
        
        // /chatpurity reload - 重载配置
        root.then(literal("reload")
            .executes(ChatPurityCommand::reloadConfig));

        // /chatpurity list - 列出所有配置
        root.then(literal("list")
            .executes(ChatPurityCommand::listConfig));
        
        // ===== 白名单命令 =====
        // /chatpurity whitelist add <词>
        root.then(literal("whitelist")
            .then(literal("add")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> addWhitelist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("remove")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> removeWhitelist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("list")
                .executes(ChatPurityCommand::listWhitelist)));

        // ===== 黑名单命令 =====
        // /chatpurity blacklist add <词>
        root.then(literal("blacklist")
            .then(literal("add")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> addBlacklist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("remove")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> removeBlacklist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("list")
                .executes(ChatPurityCommand::listBlacklist)));

        // ===== 单词黑名单命令 =====
        // /chatpurity wordblacklist add <词>
        root.then(literal("wordblacklist")
            .then(literal("add")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> addWordBlacklist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("remove")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> removeWordBlacklist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("list")
                .executes(ChatPurityCommand::listWordBlacklist)));

        // ===== 转换词命令 =====
        // /chatpurity conversion add <原词> <新词>
        root.then(literal("conversion")
            .then(literal("add")
                .then(argument("from", StringArgumentType.string())
                    .then(argument("to", StringArgumentType.greedyString())
                        .executes(ctx -> addConversion(ctx,
                            StringArgumentType.getString(ctx, "from"),
                            StringArgumentType.getString(ctx, "to"))))))
            .then(literal("remove")
                .then(argument("from", StringArgumentType.greedyString())
                    .executes(ctx -> removeConversion(ctx, StringArgumentType.getString(ctx, "from")))))
            .then(literal("list")
                .executes(ChatPurityCommand::listConversions)));

        // /chatpurity help - 显示帮助
        root.then(literal("help")
            .executes(ChatPurityCommand::showHelp));
        
        // ===== 禁言命令 =====
        // /chatpurity mute <玩家> [时长(秒)]
        root.then(literal("mute")
            .then(argument("player", StringArgumentType.word())
                .executes(ctx -> mutePlayer(ctx, StringArgumentType.getString(ctx, "player"), 300)) // 默认5分钟
                .then(argument("duration", IntegerArgumentType.integer(1))
                    .executes(ctx -> mutePlayer(ctx, 
                        StringArgumentType.getString(ctx, "player"),
                        IntegerArgumentType.getInteger(ctx, "duration"))))));
        
        // /chatpurity unmute <玩家>
        root.then(literal("unmute")
            .then(argument("player", StringArgumentType.word())
                .executes(ctx -> unmutePlayer(ctx, StringArgumentType.getString(ctx, "player")))));
        
        // /chatpurity mutelist - 查看禁言列表
        root.then(literal("mutelist")
            .executes(ChatPurityCommand::listMuted));
        
        // ===== 封禁命令 =====
        // /chatpurity unban <玩家>
        root.then(literal("unban")
            .then(argument("player", StringArgumentType.word())
                .executes(ctx -> unbanPlayer(ctx, StringArgumentType.getString(ctx, "player")))));
        
        // ===== 设置命令 =====
        root.then(literal("set")
            // 基础设置
            .then(literal("basic")
                .then(literal("enableFilter")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableFilter", StringArgumentType.getString(ctx, "value")))))
                .then(literal("ignoreCase")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "ignoreCase", StringArgumentType.getString(ctx, "value")))))
                .then(literal("debugMode")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "debugMode", StringArgumentType.getString(ctx, "value")))))
                .then(literal("enableReleaseCompliant")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableReleaseCompliant", StringArgumentType.getString(ctx, "value"))))))
            // 白名单设置
            .then(literal("whitelist")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableWhitelist", StringArgumentType.getString(ctx, "value"))))))
            // 黑名单设置
            .then(literal("blacklist")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableBlacklist", StringArgumentType.getString(ctx, "value")))))
                .then(literal("enableRegex")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableRegex", StringArgumentType.getString(ctx, "value")))))
                .then(literal("matchMode")
                    .then(argument("mode", StringArgumentType.word())
                        .executes(ctx -> setString(ctx, "blacklistMatchMode", StringArgumentType.getString(ctx, "mode"))))))
            // 单词黑名单设置
            .then(literal("wordblacklist")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableWordBlacklist", StringArgumentType.getString(ctx, "value")))))
                .then(literal("threshold")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setInt(ctx, "wordBlacklistThreshold", StringArgumentType.getString(ctx, "value"))))))
            // 转换词设置
            .then(literal("conversion")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableConversions", StringArgumentType.getString(ctx, "value")))))
                .then(literal("matchMode")
                    .then(argument("mode", StringArgumentType.word())
                        .executes(ctx -> setString(ctx, "conversionMatchMode", StringArgumentType.getString(ctx, "mode")))))
                .then(literal("threshold")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setInt(ctx, "conversionThreshold", StringArgumentType.getString(ctx, "value"))))))
            // 夹杂词黑名单设置
            .then(literal("mixedblacklist")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableMixedBlacklist", StringArgumentType.getString(ctx, "value")))))
                .then(literal("threshold")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setInt(ctx, "mixedBlacklistThreshold", StringArgumentType.getString(ctx, "value")))))
                .then(literal("ignoreChars")
                    .then(argument("chars", StringArgumentType.string())
                        .executes(ctx -> setString(ctx, "mixedBlacklistIgnoreChars", StringArgumentType.getString(ctx, "chars")))))
                .then(literal("enableDisorderDetection")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableMixedDisorderDetection", StringArgumentType.getString(ctx, "value"))))))
            // 日志设置
            .then(literal("log")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableLog", StringArgumentType.getString(ctx, "value")))))
                .then(literal("path")
                    .then(argument("path", StringArgumentType.string())
                        .executes(ctx -> setString(ctx, "logPath", StringArgumentType.getString(ctx, "path")))))
                .then(literal("logPlayerName")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "logPlayerName", StringArgumentType.getString(ctx, "value")))))
                .then(literal("logTimestamp")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "logTimestamp", StringArgumentType.getString(ctx, "value"))))))
            // 警告设置
            .then(literal("warning")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableWarning", StringArgumentType.getString(ctx, "value")))))
                .then(literal("maxWarnings")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setInt(ctx, "maxWarnings", StringArgumentType.getString(ctx, "value")))))
                .then(literal("punishment")
                    .then(argument("type", StringArgumentType.word())
                        .executes(ctx -> setString(ctx, "warningPunishment", StringArgumentType.getString(ctx, "type"))))))
            // 防绕过设置
            .then(literal("antibypass")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableAntiBypass", StringArgumentType.getString(ctx, "value")))))
                .then(literal("detectColorCodes")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "detectColorCodes", StringArgumentType.getString(ctx, "value")))))
                .then(literal("detectUnicodeVariants")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "detectUnicodeVariants", StringArgumentType.getString(ctx, "value")))))
                .then(literal("detectPinyinMix")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "detectPinyinMix", StringArgumentType.getString(ctx, "value")))))
                .then(literal("detectHomophones")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "detectHomophones", StringArgumentType.getString(ctx, "value"))))))
            // 临时封禁设置
            .then(literal("tempban")
                .then(literal("enable")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "enableTempBan", StringArgumentType.getString(ctx, "value")))))
                .then(literal("violations")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setInt(ctx, "tempBanViolations", StringArgumentType.getString(ctx, "value")))))
                .then(literal("duration")
                    .then(argument("duration", StringArgumentType.string())
                        .executes(ctx -> setString(ctx, "tempBanDuration", StringArgumentType.getString(ctx, "duration"))))))
            // 管理员通知设置
            .then(literal("notify")
                .then(literal("admins")
                    .then(argument("value", StringArgumentType.word())
                        .executes(ctx -> setBoolean(ctx, "notifyAdmins", StringArgumentType.getString(ctx, "value")))))));
        
        dispatcher.register(root);
    }
    
    // ===== 基础命令 =====
    
    /**
     * 显示帮助信息
     * @param context 命令上下文
     * @return 命令执行结果（1 表示成功）
     */
    private static int showHelp(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        
        source.sendFeedback(() -> Text.literal("§6========== ChatPurity 聊天过滤模组 =========="), false);
        source.sendFeedback(() -> Text.literal("§6版本: 2.0.0"), false);
        source.sendFeedback(() -> Text.literal("§6====================================="), false);
        source.sendFeedback(() -> Text.literal(""), false);

        // 基础命令
        source.sendFeedback(() -> Text.literal("§a基础命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity help§7 - 显示帮助信息"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity reload§7 - 重载配置文件"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity list§7 - 列出所有配置"), false);
        source.sendFeedback(() -> Text.literal(""), false);

        // 白名单命令
        source.sendFeedback(() -> Text.literal("§a白名单命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity whitelist add <词>§7 - 添加白名单词"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity whitelist remove <词>§7 - 移除白名单词"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity whitelist list§7 - 列出白名单"), false);
        source.sendFeedback(() -> Text.literal(""), false);

        // 黑名单命令
        source.sendFeedback(() -> Text.literal("§a黑名单命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity blacklist add <词>§7 - 添加黑名单词"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity blacklist remove <词>§7 - 移除黑名单词"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity blacklist list§7 - 列出黑名单"), false);
        source.sendFeedback(() -> Text.literal(""), false);

        // 单词黑名单命令
        source.sendFeedback(() -> Text.literal("§a单词黑名单命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity wordblacklist add <词>§7 - 添加单词黑名单"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity wordblacklist remove <词>§7 - 移除单词黑名单"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity wordblacklist list§7 - 列出单词黑名单"), false);
        source.sendFeedback(() -> Text.literal(""), false);

        // 转换词命令
        source.sendFeedback(() -> Text.literal("§a转换词命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity conversion add <原词> <新词>§7 - 添加转换词"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity conversion remove <原词>§7 - 移除转换词"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity conversion list§7 - 列出转换词"), false);
        source.sendFeedback(() -> Text.literal(""), false);

        // 设置命令
        source.sendFeedback(() -> Text.literal("§a设置命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set basic enableFilter <true/false>§7 - 启用/禁用过滤"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set basic enableReleaseCompliant <true/false>§7 - 合规释放模式"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set whitelist enable <true/false>§7 - 白名单开关"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set blacklist enable <true/false>§7 - 黑名单开关"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set mixedblacklist enable <true/false>§7 - 夹杂词黑名单"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set log enable <true/false>§7 - 日志记录"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set warning enable <true/false>§7 - 警告机制"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set antibypass enable <true/false>§7 - 防绕过检测"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set tempban enable <true/false>§7 - 临时封禁"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity set notify admins <true/false>§7 - 管理员通知"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        
        // 管理命令
        source.sendFeedback(() -> Text.literal("§a管理命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity mute <玩家> [时长]§7 - 禁言玩家(默认5分钟)"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity unmute <玩家>§7 - 解除禁言"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity mutelist§7 - 查看禁言列表"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity unban <玩家>§7 - 解除临时封禁"), false);
        source.sendFeedback(() -> Text.literal(""), false);

        source.sendFeedback(() -> Text.literal("§6====================================="), false);
        source.sendFeedback(() -> Text.literal("§7提示: 所有设置修改后会自动保存到配置文件"), false);
        source.sendFeedback(() -> Text.literal("§7提示: 使用 /chatpurity reload 可热重载配置"), false);
        source.sendFeedback(() -> Text.literal("§6====================================="), false);
        
        return 1;
    }
    
    /**
     * 重载配置文件
     * @param context 命令上下文
     * @return 命令执行结果（1 表示成功，0 表示失败）
     */
    private static int reloadConfig(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }
        config.load();
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 配置已重新加载！"), true);
        return 1;
    }

    /**
     * 列出所有配置信息
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int listConfig(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        var source = context.getSource();
        source.sendFeedback(() -> Text.literal("§6[ChatPurity] ========== 配置列表 =========="), false);
        source.sendFeedback(() -> Text.literal("§e白名单 §7(" + config.getWhitelist().size() + "个): §f" + String.join(", ", config.getWhitelist())), false);
        source.sendFeedback(() -> Text.literal("§e黑名单 §7(" + config.getBlacklist().size() + "个): §f" + String.join(", ", config.getBlacklist())), false);
        source.sendFeedback(() -> Text.literal("§e单词黑名单 §7(" + config.getWordBlacklist().size() + "个): §f" + String.join(", ", config.getWordBlacklist())), false);
        source.sendFeedback(() -> Text.literal("§e转换词 §7(" + config.getConversions().size() + "个): §f" + 
            config.getConversions().entrySet().stream()
                .map(e -> e.getKey() + "→" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("无")), false);
        return 1;
    }
    
    // ===== 白名单 =====
    
    /**
     * 添加白名单词
     * @param context 命令上下文
     * @param word 要添加的词汇
     * @return 命令执行结果
     */
    private static int addWhitelist(CommandContext<ServerCommandSource> context, String word) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        if (config.getWhitelist().contains(word)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 白名单中已存在: " + word), false);
            return 0;
        }

        config.addToWhitelist(word);
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已添加到白名单: " + word), false);
        return 1;
    }

    /**
     * 移除白名单词
     * @param context 命令上下文
     * @param word 要移除的词汇
     * @return 命令执行结果
     */
    private static int removeWhitelist(CommandContext<ServerCommandSource> context, String word) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        if (!config.getWhitelist().contains(word)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 白名单中不存在: " + word), false);
            return 0;
        }

        config.removeFromWhitelist(word);
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已从白名单移除: " + word), false);
        return 1;
    }

    /**
     * 列出白名单
     * @param context 命令上下文
     * @return 命令执行结果
     */
    private static int listWhitelist(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        var list = config.getWhitelist();
        context.getSource().sendFeedback(() -> Text.literal("§e[ChatPurity] 白名单 §7(" + list.size() + "个): §f" + String.join(", ", list)), false);
        return 1;
    }
    
    // ===== 黑名单 =====
    
    private static int addBlacklist(CommandContext<ServerCommandSource> context, String word) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        if (config.getBlacklist().contains(word)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 黑名单中已存在: " + word), false);
            return 0;
        }

        config.addToBlacklist(word);
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已添加到黑名单: " + word), false);
        return 1;
    }

    private static int removeBlacklist(CommandContext<ServerCommandSource> context, String word) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        if (!config.getBlacklist().contains(word)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 黑名单中不存在: " + word), false);
            return 0;
        }

        config.removeFromBlacklist(word);
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已从黑名单移除: " + word), false);
        return 1;
    }

    private static int listBlacklist(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        var list = config.getBlacklist();
        context.getSource().sendFeedback(() -> Text.literal("§e[ChatPurity] 黑名单 §7(" + list.size() + "个): §f" + String.join(", ", list)), false);
        return 1;
    }
    
    // ===== 单词黑名单 =====
    
    private static int addWordBlacklist(CommandContext<ServerCommandSource> context, String word) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        if (config.getWordBlacklist().contains(word)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 单词黑名单中已存在: " + word), false);
            return 0;
        }

        config.addToWordBlacklist(word);
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已添加到单词黑名单: " + word), false);
        return 1;
    }

    private static int removeWordBlacklist(CommandContext<ServerCommandSource> context, String word) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        if (!config.getWordBlacklist().contains(word)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 单词黑名单中不存在: " + word), false);
            return 0;
        }

        config.removeFromWordBlacklist(word);
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已从单词黑名单移除: " + word), false);
        return 1;
    }

    private static int listWordBlacklist(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        var list = config.getWordBlacklist();
        context.getSource().sendFeedback(() -> Text.literal("§e[ChatPurity] 单词黑名单 §7(" + list.size() + "个): §f" + String.join(", ", list)), false);
        return 1;
    }
    
    // ===== 转换词 =====
    
    private static int addConversion(CommandContext<ServerCommandSource> context, String from, String to) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        // 完全支持中文，不做限制
        config.addConversion(from, to);
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已添加转换词: " + from + " → " + to), false);
        return 1;
    }
    
    private static int removeConversion(CommandContext<ServerCommandSource> context, String from) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        if (!config.getConversions().containsKey(from)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 转换词中不存在: " + from), false);
            return 0;
        }

        config.removeConversion(from);
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已移除转换词: " + from), false);
        return 1;
    }

    private static int listConversions(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        var map = config.getConversions();
        if (map.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§e[ChatPurity] 转换词: §f无"), false);
        } else {
            String list = map.entrySet().stream()
                .map(e -> e.getKey() + "→" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("无");
            context.getSource().sendFeedback(() -> Text.literal("§e[ChatPurity] 转换词 §7(" + map.size() + "个): §f" + list), false);
        }
        return 1;
    }
    
    // ===== 设置命令 =====
    
    private static int setBoolean(CommandContext<ServerCommandSource> context, String field, String value) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }
        
        boolean enabled = Boolean.parseBoolean(value);
        switch (field) {
            case "enableFilter":
                config.setEnableFilter(enabled);
                break;
            case "ignoreCase":
                config.setIgnoreCase(enabled);
                break;
            case "debugMode":
                config.setDebugMode(enabled);
                break;
            case "enableReleaseCompliant":
                config.setEnableReleaseCompliant(enabled);
                break;
            case "enableWhitelist":
                config.setEnableWhitelist(enabled);
                break;
            case "enableBlacklist":
                config.setEnableBlacklist(enabled);
                break;
            case "enableRegex":
                config.setEnableRegex(enabled);
                break;
            case "enableWordBlacklist":
                config.setEnableWordBlacklist(enabled);
                break;
            case "enableConversions":
                config.setEnableConversions(enabled);
                break;
            case "enableMixedBlacklist":
                config.setEnableMixedBlacklist(enabled);
                break;
            case "enableMixedDisorderDetection":
                config.setEnableMixedDisorderDetection(enabled);
                break;
            case "enableLog":
                config.setEnableLog(enabled);
                break;
            case "logPlayerName":
                config.setLogPlayerName(enabled);
                break;
            case "logTimestamp":
                config.setLogTimestamp(enabled);
                break;
            case "enableWarning":
                config.setEnableWarning(enabled);
                break;
            case "enableAntiBypass":
                config.setEnableAntiBypass(enabled);
                break;
            case "detectColorCodes":
                config.setDetectColorCodes(enabled);
                break;
            case "detectUnicodeVariants":
                config.setDetectUnicodeVariants(enabled);
                break;
            case "detectPinyinMix":
                config.setDetectPinyinMix(enabled);
                break;
            case "detectHomophones":
                config.setDetectHomophones(enabled);
                break;
            case "enableTempBan":
                config.setEnableTempBan(enabled);
                break;
            case "notifyAdmins":
                config.setNotifyAdmins(enabled);
                break;
            default:
                context.getSource().sendError(Text.literal("§c[ChatPurity] 未知的设置字段: " + field));
                return 0;
        }

        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] " + field + " 已设置为: " + enabled), true);
        return 1;
    }

    private static int setInt(CommandContext<ServerCommandSource> context, String field, String value) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        try {
            int intValue = Integer.parseInt(value);
            switch (field) {
                case "wordBlacklistThreshold":
                    config.setWordBlacklistThreshold(intValue);
                    break;
                case "conversionThreshold":
                    config.setConversionThreshold(intValue);
                    break;
                case "mixedBlacklistThreshold":
                    config.setMixedBlacklistThreshold(intValue);
                    break;
                case "maxWarnings":
                    config.setMaxWarnings(intValue);
                    break;
                case "tempBanViolations":
                    config.setTempBanViolations(intValue);
                    break;
                default:
                    context.getSource().sendError(Text.literal("§c[ChatPurity] 未知的设置字段: " + field));
                    return 0;
            }

            context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] " + field + " 已设置为: " + intValue), true);
            return 1;
        } catch (NumberFormatException e) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 无效的数值: " + value));
            return 0;
        }
    }

    private static int setString(CommandContext<ServerCommandSource> context, String field, String value) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        switch (field) {
            case "blacklistMatchMode":
                config.setBlacklistMatchMode(value);
                break;
            case "conversionMatchMode":
                config.setConversionMatchMode(value);
                break;
            case "mixedBlacklistIgnoreChars":
                config.setMixedBlacklistIgnoreChars(value);
                break;
            case "logPath":
                config.setLogPath(value);
                break;
            case "warningPunishment":
                config.setWarningPunishment(value);
                break;
            case "tempBanDuration":
                config.setTempBanDuration(value);
                break;
            default:
                context.getSource().sendError(Text.literal("§c[ChatPurity] 未知的设置字段: " + field));
                return 0;
        }

        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] " + field + " 已设置为: " + value), true);
        return 1;
    }
    
    // ===== 禁言管理 =====
    
    /**
     * 禁言玩家
     */
    private static int mutePlayer(CommandContext<ServerCommandSource> context, String playerName, int durationSeconds) {
        var source = context.getSource();
        var server = source.getServer();
        
        // 查找玩家
        ServerPlayerEntity targetPlayer = server.getPlayerManager().getPlayer(playerName);
        if (targetPlayer == null) {
            source.sendError(Text.literal("§c[ChatPurity] 找不到玩家: " + playerName));
            return 0;
        }
        
        // 获取 WarningHandler 并禁言
        WarningHandler warningHandler = ChatPurityMod.getWarningHandler();
        if (warningHandler == null) {
            source.sendError(Text.literal("§c[ChatPurity] 警告处理器未初始化"));
            return 0;
        }
        
        warningHandler.mutePlayer(targetPlayer, durationSeconds);
        
        String duration = ChatPurityUtils.formatDuration(durationSeconds);
        source.sendFeedback(() -> Text.literal("§a[ChatPurity] 已禁言玩家 " + playerName + " " + duration), true);
        
        // 通知被禁言的玩家
        targetPlayer.sendMessage(Text.literal("§c[ChatPurity] 您已被禁言 " + duration), false);
        
        return 1;
    }
    
    /**
     * 解除禁言
     */
    private static int unmutePlayer(CommandContext<ServerCommandSource> context, String playerName) {
        var source = context.getSource();
        var server = source.getServer();
        
        // 查找玩家
        ServerPlayerEntity targetPlayer = server.getPlayerManager().getPlayer(playerName);
        if (targetPlayer == null) {
            source.sendError(Text.literal("§c[ChatPurity] 找不到玩家: " + playerName));
            return 0;
        }
        
        // 获取 WarningHandler 并解除禁言
        WarningHandler warningHandler = ChatPurityMod.getWarningHandler();
        if (warningHandler == null) {
            source.sendError(Text.literal("§c[ChatPurity] 警告处理器未初始化"));
            return 0;
        }
        
        if (warningHandler.unmutePlayer(targetPlayer)) {
            source.sendFeedback(() -> Text.literal("§a[ChatPurity] 已解除玩家 " + playerName + " 的禁言"), true);
            targetPlayer.sendMessage(Text.literal("§a[ChatPurity] 您的禁言已被解除"), false);
            return 1;
        } else {
            source.sendFeedback(() -> Text.literal("§e[ChatPurity] 玩家 " + playerName + " 未被禁言"), false);
            return 0;
        }
    }
    
    /**
     * 查看禁言列表
     */
    private static int listMuted(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        var server = source.getServer();
        
        source.sendFeedback(() -> Text.literal("§6[ChatPurity] ========== 禁言列表 =========="), false);
        
        boolean found = false;
        
        // 检查警告禁言
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            WarningHandler warningHandler = ChatPurityMod.getWarningHandler();
            if (warningHandler != null && warningHandler.isMuted(player)) {
                long remaining = warningHandler.getRemainingMuteTime(player);
                source.sendFeedback(() -> Text.literal("  §e" + player.getName().getString() + " §7[警告禁言] §f- 剩余: " + ChatPurityUtils.formatDuration(remaining)), false);
                found = true;
            }
        }
        
        // 检查防刷屏禁言
        AntiSpamHandler antiSpamHandler = ChatPurityMod.getAntiSpamHandler();
        if (antiSpamHandler != null) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (antiSpamHandler.isMuted(player)) {
                    long remaining = antiSpamHandler.getRemainingMuteTime(player);
                    source.sendFeedback(() -> Text.literal("  §e" + player.getName().getString() + " §7[刷屏禁言] §f- 剩余: " + ChatPurityUtils.formatDuration(remaining)), false);
                    found = true;
                }
            }
        }
        
        if (!found) {
            source.sendFeedback(() -> Text.literal("§7当前没有玩家被禁言"), false);
        }
        
        return 1;
    }
    
    /**
     * 解除临时封禁
     */
    private static int unbanPlayer(CommandContext<ServerCommandSource> context, String playerName) {
        var source = context.getSource();
        var server = source.getServer();
        
        // 查找玩家（可能不在线）
        ServerPlayerEntity targetPlayer = server.getPlayerManager().getPlayer(playerName);
        
        TempBanHandler tempBanHandler = ChatPurityMod.getTempBanHandler();
        if (tempBanHandler == null) {
            source.sendError(Text.literal("§c[ChatPurity] 封禁处理器未初始化"));
            return 0;
        }
        
        if (targetPlayer != null) {
            // 玩家在线
            if (tempBanHandler.unbanPlayer(server, targetPlayer)) {
                source.sendFeedback(() -> Text.literal("§a[ChatPurity] 已解除玩家 " + playerName + " 的封禁"), true);
                return 1;
            } else {
                source.sendFeedback(() -> Text.literal("§e[ChatPurity] 玩家 " + playerName + " 未被封禁"), false);
                return 0;
            }
        } else {
            // 玩家不在线，提示
            source.sendFeedback(() -> Text.literal("§e[ChatPurity] 玩家 " + playerName + " 不在线，无法通过命令解封"), false);
            source.sendFeedback(() -> Text.literal("§7提示: 请使用 /pardon 命令或编辑 banned-players.json"), false);
            return 0;
        }
    }
}