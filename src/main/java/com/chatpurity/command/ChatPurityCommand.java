package com.chatpurity.command;

import com.chatpurity.ChatPurityMod;
import com.chatpurity.util.ChatPurityUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ChatPurityCommand {
    private static final int OP_LEVEL = 4;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root = literal("chatpurity")
            .requires(source -> source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.fromLevel(OP_LEVEL))));
        
        root.then(literal("reload")
            .executes(ChatPurityCommand::reloadConfig));

        root.then(literal("whitelist")
            .then(literal("add")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> addWhitelist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("remove")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> removeWhitelist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("list")
                .executes(ChatPurityCommand::listWhitelist)));

        root.then(literal("blacklist")
            .then(literal("add")
                .then(argument("args", StringArgumentType.greedyString())
                    .suggests(MODE_SUGGESTION)
                    .executes(ctx -> addBlacklist(ctx, 
                        StringArgumentType.getString(ctx, "args")))))
            .then(literal("remove")
                .then(argument("word", StringArgumentType.greedyString())
                    .executes(ctx -> removeBlacklist(ctx, StringArgumentType.getString(ctx, "word")))))
            .then(literal("list")
                .executes(ChatPurityCommand::listBlacklist)));

        root.then(literal("replacement")
            .then(literal("add")
                .then(argument("args", StringArgumentType.greedyString())
                    .executes(ctx -> addReplacement(ctx, 
                        StringArgumentType.getString(ctx, "args")))))
            .then(literal("remove")
                .then(argument("pattern", StringArgumentType.greedyString())
                    .executes(ctx -> removeReplacement(ctx, StringArgumentType.getString(ctx, "pattern")))))
            .then(literal("list")
                .executes(ChatPurityCommand::listReplacements)));

        root.then(literal("mute")
            .then(literal("add")
                .then(argument("player", StringArgumentType.string())
                    .then(argument("time", StringArgumentType.string())
                        .executes(ctx -> mutePlayer(ctx,
                            StringArgumentType.getString(ctx, "player"),
                            StringArgumentType.getString(ctx, "time"))))))
            .then(literal("remove")
                .then(argument("player", StringArgumentType.string())
                    .executes(ctx -> unmutePlayer(ctx, StringArgumentType.getString(ctx, "player")))))
            .then(literal("list")
                .executes(ChatPurityCommand::listMutedPlayers)));

        root.then(literal("help")
            .executes(ChatPurityCommand::showHelp));
        
        dispatcher.register(root);
    }

    private static final SuggestionProvider<ServerCommandSource> MODE_SUGGESTION = (ctx, builder) -> {
        builder.suggest("", Text.literal("包含检测（默认）- 只要包含指定词就屏蔽"));
        builder.suggest("homophone", Text.literal("同音词检测 - 检测同音词、同音字"));
        builder.suggest("pinyin_abbr", Text.literal("拼音缩写检测 - 检测拼音首字母（如sb=傻逼）"));
        builder.suggest("pinyin_full", Text.literal("完整拼音检测 - 检测完整拼音（如shabi=傻逼）"));
        builder.suggest("exact_match", Text.literal("精确匹配 - 只检测完全相同的词或重复词"));
        builder.suggest("homophone:pinyin_abbr:pinyin_full", Text.literal("组合模式 - 同时检测同音词、拼音缩写、完整拼音"));
        builder.suggest("op1", Text.literal("OP1及以下会被屏蔽，OP2/3/4可发送"));
        builder.suggest("op2", Text.literal("OP2及以下会被屏蔽，OP3/4可发送"));
        builder.suggest("op3", Text.literal("OP3及以下会被屏蔽，OP4可发送"));
        builder.suggest("op4", Text.literal("所有人都会被屏蔽（包括OP4）"));
        return builder.buildFuture();
    };

    private static int showHelp(CommandContext<ServerCommandSource> context) {
        var source = context.getSource();
        source.sendFeedback(() -> Text.literal("§6========== ChatPurity 帮助 =========="), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§a基础命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity reload§7 - 重载配置文件"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§a白名单命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity whitelist add <词>"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity whitelist remove <词>"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity whitelist list"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§a黑名单命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity blacklist add <词> [模式]"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity blacklist remove <词>"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity blacklist list"), false);
        source.sendFeedback(() -> Text.literal("  §7模式:"), false);
        source.sendFeedback(() -> Text.literal("    §7(无)=包含 | homophone=同音词 | pinyin_abbr=拼音缩写"), false);
        source.sendFeedback(() -> Text.literal("    §7pinyin_full=完整拼音 | exact_match=精确 | op1-4=OP权限"), false);
        source.sendFeedback(() -> Text.literal("  §7OP权限: op1=OP1及以下屏蔽, op2=OP2及以下屏蔽..."), false);
        source.sendFeedback(() -> Text.literal("  §7示例: /chatpurity blacklist add 傻逼 homophone:pinyin_abbr:pinyin_full"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§a替换词命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity replacement add <原词> <替换词> [模式]"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity replacement remove <原词>"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity replacement list"), false);
        source.sendFeedback(() -> Text.literal("  §7模式: homophone | pinyin_abbr | pinyin_full | exact_match | op1-4"), false);
        source.sendFeedback(() -> Text.literal("  §7示例: /chatpurity replacement add 卧槽 *** homophone:pinyin_abbr:pinyin_full"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§a禁言命令:"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity mute add <玩家> <时间>"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity mute remove <玩家>"), false);
        source.sendFeedback(() -> Text.literal("  §e/chatpurity mute list"), false);
        source.sendFeedback(() -> Text.literal("  §7时间: 10s / 5min / 2h / 1d / -1(永久)"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§6===================================="), false);
        return 1;
    }

    private static int reloadConfig(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }
        config.reload();
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 配置已重新加载！"), true);
        return 1;
    }

    private static int addWhitelist(CommandContext<ServerCommandSource> context, String word) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        // 参数验证
        if (word == null || word.trim().isEmpty()) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 词不能为空"));
            return 0;
        }
        
        final String finalWord = word.trim();
        
        // 检查是否包含非法字符（防止YAML注入等安全问题）
        if (finalWord.contains("\n") || finalWord.contains("\r") || finalWord.contains("\0")) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 词包含非法字符"));
            return 0;
        }

        if (config.getWhitelist().contains(finalWord)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 白名单中已存在: " + finalWord), false);
            return 0;
        }

        config.getWhitelist().add(finalWord);
        config.save();
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已添加到白名单: " + finalWord), false);
        return 1;
    }

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

        config.getWhitelist().remove(word);
        config.save();
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已从白名单移除: " + word), false);
        return 1;
    }

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

    /**
     * 添加黑名单规则
     * 格式: /chatpurity blacklist add 词 [模式]
     * 示例: /chatpurity blacklist add 傻逼
     *       /chatpurity blacklist add 傻逼 homophone
     *       /chatpurity blacklist add 傻逼 homophone:op2
     *       /chatpurity blacklist add "[我,喜,欢]" homophone
     */
    private static int addBlacklist(CommandContext<ServerCommandSource> context, String args) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        // 参数验证
        if (args == null || args.trim().isEmpty()) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 用法: /chatpurity blacklist add <词> [模式]"));
            context.getSource().sendError(Text.literal("§c[ChatPurity] 示例: /chatpurity blacklist add 傻逼"));
            context.getSource().sendError(Text.literal("§c[ChatPurity]       /chatpurity blacklist add 傻逼 homophone"));
            return 0;
        }
        
        // 解析参数：格式为 "词 [模式]"
        String[] parts = args.trim().split("\\s+", 2); // 最多分成2部分
        
        String word = parts[0];
        String mode = parts.length > 1 ? parts[1] : "";
        
        final String finalWord = word;
        final String finalMode = mode;
        
        // 检查是否包含非法字符（防止YAML注入等安全问题）
        if (finalWord.contains("\n") || finalWord.contains("\r") || finalWord.contains("\0")) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 词包含非法字符"));
            return 0;
        }
        
        if (finalMode.contains("\n") || finalMode.contains("\r") || finalMode.contains("\0")) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 模式包含非法字符"));
            return 0;
        }

        if (config.getBlacklist().containsKey(finalWord)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 黑名单中已存在: " + finalWord), false);
            return 0;
        }

        config.getBlacklist().put(finalWord, finalMode);
        config.save();
        final String modeText = finalMode.isEmpty() ? "默认（包含检测）" : finalMode;
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已添加到黑名单: " + finalWord + " (模式: " + modeText + ")"), false);
        return 1;
    }

    /**
     * 移除黑名单规则
     * 支持智能匹配：输入词即可移除，无需输入模式
     * 例如：如果存储的是 "傻逼:homophone"，用户只需输入 "傻逼" 即可移除
     */
    private static int removeBlacklist(CommandContext<ServerCommandSource> context, String word) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        // 先尝试精确匹配
        if (config.getBlacklist().containsKey(word)) {
            config.getBlacklist().remove(word);
            config.save();
            context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已从黑名单移除: " + word), false);
            return 1;
        }
        
        // 尝试模糊匹配：查找以 word 或 "word:" 开头的键
        List<String> matches = new ArrayList<>();
        for (String key : config.getBlacklist().keySet()) {
            // 检查键是否等于 word，或者以 "word:" 开头（带模式的情况）
            if (key.equals(word) || key.startsWith(word + ":")) {
                matches.add(key);
            }
        }
        
        if (matches.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 黑名单中不存在: " + word), false);
            return 0;
        }
        
        if (matches.size() == 1) {
            // 只有一个匹配，直接移除
            String keyToRemove = matches.get(0);
            config.getBlacklist().remove(keyToRemove);
            config.save();
            context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已从黑名单移除: " + keyToRemove), false);
            return 1;
        }
        
        // 多个匹配，列出让用户选择
        context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 找到多个匹配的规则，请指定完整名称:"), false);
        for (String match : matches) {
            String mode = config.getBlacklist().get(match);
            String modeText = mode.isEmpty() ? "默认" : mode;
            context.getSource().sendFeedback(() -> Text.literal("§7  - \"" + match + "\" [§e" + modeText + "§7]"), false);
        }
        return 0;
    }

    private static int listBlacklist(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        var map = config.getBlacklist();
        if (map.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§e[ChatPurity] 黑名单: §f无"), false);
        } else {
            StringBuilder sb = new StringBuilder("§e[ChatPurity] 黑名单 §7(" + map.size() + "个):\n");
            for (Map.Entry<String, String> entry : map.entrySet()) {
                String modeText = entry.getValue().isEmpty() ? "默认" : entry.getValue();
                sb.append("§f").append(entry.getKey()).append(" §7[§e").append(modeText).append("§7]§7, ");
            }
            String list = sb.toString();
            if (list.endsWith(", ")) {
                list = list.substring(0, list.length() - 2);
            }
            final String finalList = list;
            context.getSource().sendFeedback(() -> Text.literal(finalList), false);
        }
        return 1;
    }

    /**
     * 添加替换词规则
     * 格式: /chatpurity replacement add 原词 替换词 [模式]
     * 示例: /chatpurity replacement add 卧槽 ***
     *       /chatpurity replacement add 卧槽 *** homophone
     *       /chatpurity replacement add "[我,哎]" L homophone
     */
    private static int addReplacement(CommandContext<ServerCommandSource> context, String args) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        // 参数验证
        if (args == null || args.trim().isEmpty()) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 用法: /chatpurity replacement add <原词> <替换词> [模式]"));
            context.getSource().sendError(Text.literal("§c[ChatPurity] 示例: /chatpurity replacement add 卧槽 ***"));
            context.getSource().sendError(Text.literal("§c[ChatPurity]       /chatpurity replacement add 卧槽 *** homophone"));
            return 0;
        }
        
        // 解析参数：格式为 "原词 替换词 [模式]"
        // 模式会追加到原词后面，格式为 "原词:模式"
        String[] parts = args.trim().split("\\s+", 3); // 最多分成3部分
        
        if (parts.length < 2) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 用法: /chatpurity replacement add <原词> <替换词> [模式]"));
            context.getSource().sendError(Text.literal("§c[ChatPurity] 示例: /chatpurity replacement add 卧槽 ***"));
            return 0;
        }
        
        String pattern = parts[0];
        String replacement = parts[1];
        String mode = parts.length > 2 ? parts[2] : "";
        
        // 如果有模式，追加到原词后面，格式为 "原词:模式"
        if (!mode.isEmpty()) {
            pattern = pattern + ":" + mode;
        }
        
        final String finalPattern = pattern;
        final String finalReplacement = replacement;
        
        // 检查是否包含非法字符（防止YAML注入等安全问题）
        if (finalPattern.contains("\n") || finalPattern.contains("\r") || finalPattern.contains("\0")) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 原词包含非法字符"));
            return 0;
        }
        
        if (finalReplacement.contains("\n") || finalReplacement.contains("\r") || finalReplacement.contains("\0")) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 替换词包含非法字符"));
            return 0;
        }

        if (config.getReplacements().containsKey(finalPattern)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 替换规则中已存在: " + finalPattern), false);
            return 0;
        }

        config.getReplacements().put(finalPattern, finalReplacement);
        config.save();
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已添加替换规则: " + finalPattern + " → " + finalReplacement), false);
        return 1;
    }

    /**
     * 移除替换词规则
     * 支持智能匹配：输入原词即可移除，无需输入模式
     * 例如：如果存储的是 "好:homophone"，用户只需输入 "好" 即可移除
     */
    private static int removeReplacement(CommandContext<ServerCommandSource> context, String pattern) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        // 先尝试精确匹配
        if (config.getReplacements().containsKey(pattern)) {
            config.getReplacements().remove(pattern);
            config.save();
            context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已移除替换规则: " + pattern), false);
            return 1;
        }
        
        // 尝试模糊匹配：查找以 pattern 或 "pattern:" 开头的键
        List<String> matches = new ArrayList<>();
        for (String key : config.getReplacements().keySet()) {
            // 检查键是否等于 pattern，或者以 "pattern:" 开头（带模式的情况）
            if (key.equals(pattern) || key.startsWith(pattern + ":")) {
                matches.add(key);
            }
        }
        
        if (matches.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 替换规则中不存在: " + pattern), false);
            return 0;
        }
        
        if (matches.size() == 1) {
            // 只有一个匹配，直接移除
            String keyToRemove = matches.get(0);
            config.getReplacements().remove(keyToRemove);
            config.save();
            context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已移除替换规则: " + keyToRemove), false);
            return 1;
        }
        
        // 多个匹配，列出让用户选择
        context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 找到多个匹配的规则，请指定完整名称:"), false);
        for (String match : matches) {
            String replacement = config.getReplacements().get(match);
            context.getSource().sendFeedback(() -> Text.literal("§7  - \"" + match + "\" → \"" + replacement + "\""), false);
        }
        return 0;
    }

    private static int listReplacements(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        var map = config.getReplacements();
        if (map.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§e[ChatPurity] 替换规则: §f无"), false);
        } else {
            StringBuilder sb = new StringBuilder("§e[ChatPurity] 替换规则 §7(" + map.size() + "个):\n");
            for (Map.Entry<String, String> entry : map.entrySet()) {
                sb.append("§f").append(entry.getKey()).append(" §7→ §e").append(entry.getValue()).append("§7, ");
            }
            String list = sb.toString();
            if (list.endsWith(", ")) {
                list = list.substring(0, list.length() - 2);
            }
            final String finalList = list;
            context.getSource().sendFeedback(() -> Text.literal(finalList), false);
        }
        return 1;
    }

    private static int mutePlayer(CommandContext<ServerCommandSource> context, String playerName, String timeStr) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        // 验证玩家名格式
        // Minecraft 玩家名规则：3-16字符，支持字母、数字、下划线，以及部分服务器支持中文
        if (playerName == null || playerName.trim().isEmpty()) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 玩家名不能为空"));
            return 0;
        }
        
        String trimmedName = playerName.trim();
        if (trimmedName.length() < 3 || trimmedName.length() > 16) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 玩家名长度必须在3-16个字符之间"));
            return 0;
        }
        
        // 放宽玩家名验证，支持中文和更多 Unicode 字符
        // 仅排除控制字符和可能导致问题的特殊字符
        if (trimmedName.matches(".*[\\x00-\\x1F\\x7F].*")) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 玩家名包含非法字符"));
            return 0;
        }

        var server = context.getSource().getServer();
        var player = server.getPlayerManager().getPlayer(trimmedName);

        if (player == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 玩家不存在或未在线: " + playerName));
            return 0;
        }

        long seconds = ChatPurityUtils.parseTime(timeStr);
        boolean isPermanent = "-1".equals(timeStr.trim().toLowerCase());
        
        if (seconds <= 0 && !isPermanent) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 无效的时间格式，例如: 10s, 5min, 2h, 1d"));
            return 0;
        }

        long muteEndTime = isPermanent ? Long.MAX_VALUE : ChatPurityUtils.calculateMuteEndTime(seconds);
        config.getMutedPlayers().put(trimmedName, muteEndTime);
        config.save();
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已禁言玩家 " + trimmedName + " " + timeStr), true);
        player.sendMessage(Text.literal("§c[ChatPurity] 你已被禁言 " + timeStr));
        return 1;
    }

    private static int unmutePlayer(CommandContext<ServerCommandSource> context, String playerName) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        if (!config.getMutedPlayers().containsKey(playerName)) {
            context.getSource().sendFeedback(() -> Text.literal("§c[ChatPurity] 玩家未被禁言: " + playerName), false);
            return 0;
        }

        config.getMutedPlayers().remove(playerName);
        config.save();
        context.getSource().sendFeedback(() -> Text.literal("§a[ChatPurity] 已解除玩家 " + playerName + " 的禁言"), true);
        
        var server = context.getSource().getServer();
        var player = server.getPlayerManager().getPlayer(playerName);
        if (player != null) {
            player.sendMessage(Text.literal("§a[ChatPurity] 你已被解除禁言"));
        }
        return 1;
    }

    private static int listMutedPlayers(CommandContext<ServerCommandSource> context) {
        var config = ChatPurityMod.getConfig();
        if (config == null) {
            context.getSource().sendError(Text.literal("§c[ChatPurity] 配置未初始化"));
            return 0;
        }

        var mutedPlayers = config.getMutedPlayers();
        if (mutedPlayers.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("§e[ChatPurity] 被禁言的玩家: §f无"), false);
        } else {
            StringBuilder sb = new StringBuilder("§e[ChatPurity] 被禁言的玩家 §7(" + mutedPlayers.size() + "个):\n");
            long currentTime = System.currentTimeMillis();
            for (Map.Entry<String, Long> entry : mutedPlayers.entrySet()) {
                long muteEndTime = entry.getValue();
                // 检查是否永久禁言
                if (muteEndTime == Long.MAX_VALUE) {
                    sb.append("§f").append(entry.getKey()).append(" §7[§c永久§7]§7, ");
                } else {
                    long remainingTime = (muteEndTime - currentTime) / 1000;
                    if (remainingTime > 0) {
                        sb.append("§f").append(entry.getKey()).append(" §7[§e").append(ChatPurityUtils.formatDuration(remainingTime)).append("§7]§7, ");
                    }
                }
            }
            String list = sb.toString();
            if (list.endsWith(", ")) {
                list = list.substring(0, list.length() - 2);
            }
            final String finalList = list;
            context.getSource().sendFeedback(() -> Text.literal(finalList), false);
        }
        return 1;
    }
}