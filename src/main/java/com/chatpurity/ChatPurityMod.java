package com.chatpurity;

import com.chatpurity.command.ChatPurityCommand;
import com.chatpurity.config.ChatPurityConfig;
import com.chatpurity.handler.ChatHandler;
import com.chatpurity.handler.LogHandler;
import com.chatpurity.handler.WarningHandler;
import com.chatpurity.handler.AntiBypassHandler;
import com.chatpurity.handler.TempBanHandler;
import com.chatpurity.handler.AdminNotifyHandler;
import com.chatpurity.handler.AntiSpamHandler;
import com.chatpurity.util.ChatPurityUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * ChatPurity 模组主类
 * 
 * <p>这是一个 Fabric Minecraft 聊天过滤模组，提供全面的聊天内容管理功能：
 * <ul>
 *   <li>白名单/黑名单过滤 - 支持精确匹配、包含匹配、正则表达式</li>
 *   <li>敏感词转换 - 自动将敏感词替换为指定内容</li>
 *   <li>防刷屏检测 - 检测重复消息和快速发送</li>
 *   <li>防绕过检测 - 检测颜色代码、Unicode变体、拼音混合等绕过方式</li>
 *   <li>警告机制 - 累计警告后执行惩罚</li>
 *   <li>临时封禁 - 频繁违规玩家自动封禁</li>
 *   <li>日志记录 - 记录所有违规消息</li>
 *   <li>管理员通知 - 实时通知管理员严重违规</li>
 * </ul>
 * 
 * <p>模组使用事件驱动架构，通过 Fabric API 的事件系统拦截和处理聊天消息。
 * 
 * @see ChatPurityConfig 配置管理
 * @see ChatHandler 聊天处理核心逻辑
 * @see WarningHandler 警告机制
 * @see AntiSpamHandler 防刷屏处理
 * @see AntiBypassHandler 防绕过检测
 */
public class ChatPurityMod implements ModInitializer {
    /** 模组ID */
    public static final String MOD_ID = "chatpurity";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static ChatPurityConfig config;
    private static ChatHandler chatHandler;
    private static LogHandler logHandler;
    private static WarningHandler warningHandler;
    private static AntiBypassHandler antiBypassHandler;
    private static TempBanHandler tempBanHandler;
    private static AdminNotifyHandler adminNotifyHandler;
    private static AntiSpamHandler antiSpamHandler;
    private static MinecraftServer server;

    /**
     * 模组初始化入口点
     * 
     * <p>注册以下事件处理器：
     * <ul>
     *   <li>服务器启动事件 - 初始化配置和处理器</li>
     *   <li>聊天消息事件 - 拦截和处理聊天消息</li>
     *   <li>命令注册事件 - 注册 /chatpurity 命令</li>
     * </ul>
     */
    @Override
    public void onInitialize() {
        // 打印启动 banner
        printBanner();
        
        try {
            ServerLifecycleEvents.SERVER_STARTING.register(thisServer -> {
                try {
                    server = thisServer;
                    Path configDir = thisServer.getRunDirectory().resolve("config/chatpurity");
                    Path configPath = configDir.resolve("chatpurity.yml");
                    config = new ChatPurityConfig(configPath);
                    chatHandler = new ChatHandler(config);
                    logHandler = new LogHandler(config);
                    logHandler.setServer(thisServer);
                    warningHandler = new WarningHandler(config);
                    antiBypassHandler = new AntiBypassHandler(config, configDir);
                    tempBanHandler = new TempBanHandler(config);
                    adminNotifyHandler = new AdminNotifyHandler(config);
                    antiSpamHandler = new AntiSpamHandler(config);
                    antiSpamHandler.setServer(thisServer);
                    LOGGER.info("Mod initialized successfully!");
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize mod", e);
                }
            });

            // 使用 ALLOW_CHAT_MESSAGE 事件拦截消息（屏蔽 + 转换）
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::onAllowChatMessage);

            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                ChatPurityCommand.register(dispatcher);
            });
        } catch (Exception e) {
            LOGGER.error("Failed to register event handlers", e);
        }
    }

    /**
     * 拦截聊天消息 - 用于屏蔽和转换功能
     * 
     * <p>处理流程：
     * <ol>
     *   <li>检查核心组件是否初始化</li>
     *   <li>检查玩家是否被禁言</li>
     *   <li>防刷屏检测</li>
     *   <li>防绕过检测</li>
     *   <li>黑名单检查</li>
     *   <li>敏感词转换</li>
     * </ol>
     * 
     * @param message 签名的聊天消息对象
     * @param sender 消息发送者
     * @param params 消息类型参数（用于消息格式化）
     * @return true 允许原消息发送，false 拦截原消息
     */
    private boolean onAllowChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params) {
        // 检查核心组件是否初始化完成
        if (config == null || chatHandler == null || server == null) {
            // 核心组件未初始化，允许消息通过但记录警告
            LOGGER.warn("Core components not initialized, allowing message through");
            return true;
        }
        
        try {
            // 首先检查玩家是否被禁言（警告禁言）
            if (warningHandler != null && warningHandler.isMuted(sender)) {
                long remaining = warningHandler.getRemainingMuteTime(sender);
                sender.sendMessage(Text.literal("§c[ChatPurity] 您已被禁言，请等待 " + ChatPurityUtils.formatDuration(remaining) + " 后再试"), true);
                return false;
            }
            
            // 检查防刷屏禁言
            if (antiSpamHandler != null && antiSpamHandler.isMuted(sender)) {
                long remaining = antiSpamHandler.getRemainingMuteTime(sender);
                sender.sendMessage(Text.literal("§c[ChatPurity] 您已被禁言，请等待 " + ChatPurityUtils.formatDuration(remaining) + " 后再试"), true);
                return false;
            }
            
            Text content = message.getContent();
            String originalText = content.getString();
            
            // 防刷屏检测
            if (antiSpamHandler != null && antiSpamHandler.shouldBlock(sender, originalText)) {
                return false;
            }
            
            // 防绕过检测
            if (config.isEnableAntiBypass() && antiBypassHandler != null) {
                if (antiBypassHandler.containsBypassAttempts(originalText)) {
                    String reason = "防绕过检测";
                    if (logHandler != null) {
                        logHandler.log(sender, originalText, "blocked", reason);
                    }
                    if (warningHandler != null) {
                        warningHandler.addWarningAndCheckPunishment(sender, server);
                    }
                    if (adminNotifyHandler != null) {
                        adminNotifyHandler.notifyAdmins(server, sender, originalText, reason);
                    }
                    if (config.isNotifyBlocked()) {
                        sender.sendMessage(Text.literal(config.getBlockedMessage()), true);
                    }
                    return false;
                }
            }
            
            // 1. 检查是否需要屏蔽（黑名单检查）
            if (chatHandler.shouldBlock(content, sender, server)) {
                String reason = "违规检测";
                if (logHandler != null) {
                    logHandler.log(sender, originalText, "blocked", reason);
                }
                
                // 警告机制（包含惩罚执行）
                if (warningHandler != null) {
                    warningHandler.addWarningAndCheckPunishment(sender, server);
                }
                
                // 临时封禁检查 - 记录违规次数
                if (config.isEnableTempBan() && tempBanHandler != null) {
                    if (tempBanHandler.recordViolation(sender, server)) {
                        return false;
                    }
                }
                
                // 管理员通知
                if (adminNotifyHandler != null) {
                    adminNotifyHandler.notifyAdmins(server, sender, originalText, reason);
                }
                
                // 使用配置中的屏蔽提示消息
                if (config.isNotifyBlocked()) {
                    sender.sendMessage(Text.literal(config.getBlockedMessage()), true);
                }
                
                // 建议功能
                if (config.isShowSuggestions()) {
                    sender.sendMessage(Text.literal(config.getSuggestionMessage()), true);
                }
                return false;
            }
            
            // 2. 检查是否需要转换
            String convertedText = chatHandler.applyConversions(originalText);
            if (!originalText.equals(convertedText)) {
                String reason = "敏感词替换";
                if (logHandler != null) {
                    logHandler.log(sender, originalText, "replaced", reason);
                }
                
                // 屏蔽原消息，发送转换后的系统消息
                // 使用配置中的格式
                String format = config.getConvertedMessageFormat();
                String formattedMessage = format
                    .replace("{player}", sender.getName().getString())
                    .replace("{message}", convertedText);
                
                // 替换提示
                if (config.isShowReplacementNotice()) {
                    sender.sendMessage(Text.literal(config.getReplacementNoticeMessage()), true);
                }
                
                // 广播给所有玩家
                server.getPlayerManager().broadcast(
                    Text.literal(formattedMessage),
                    false // 不是系统消息，作为聊天消息处理
                );
                return false; // 屏蔽原始消息
            }
        } catch (Exception e) {
            LOGGER.error("Error processing chat message", e);
            // 发生异常时，为了安全起见，阻止消息发送
            sender.sendMessage(Text.literal("§c[ChatPurity] 处理消息时发生错误，请稍后重试"), true);
            return false;
        }
        return true;
    }

    /**
     * 打印启动 banner（ASCII art）
     */
    private void printBanner() {
        System.out.println();
        System.out.println("    ██████╗██╗  ██╗ █████╗ ████████╗██████╗ ██╗   ██╗██████╗ ██╗████████╗██╗   ██╗");
        System.out.println("   ██╔════╝██║  ██║██╔══██╗╚══██╔══╝██╔══██╗██║   ██║██╔══██╗██║╚══██╔══╝╚██╗ ██╔╝");
        System.out.println("   ██║     ███████║███████║   ██║   ██████╔╝██║   ██║██████╔╝██║   ██║    ╚████╔╝ ");
        System.out.println("   ██║     ██╔══██║██╔══██║   ██║   ██╔═══╝ ██║   ██║██╔══██╗██║   ██║     ╚██╔╝  ");
        System.out.println("   ╚██████╗██║  ██║██║  ██║   ██║   ██║     ╚██████╔╝██║  ██║██║   ██║      ██║   ");
        System.out.println("    ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ╚═╝      ╚═════╝ ╚═╝  ╚═╝╚═╝   ╚═╝      ╚═╝   ");
        System.out.println();
        System.out.println("   ██████╗ ██╗   ██╗██████╗ ██╗████████╗██╗   ██╗");
        System.out.println("   ██╔══██╗██║   ██║██╔══██╗██║╚══██╔══╝╚██╗ ██╔╝");
        System.out.println("   ██████╔╝██║   ██║██████╔╝██║   ██║    ╚████╔╝ ");
        System.out.println("   ██╔═══╝ ██║   ██║██╔══██╗██║   ██║     ╚██╔╝  ");
        System.out.println("   ██║     ╚██████╔╝██║  ██║██║   ██║      ██║   ");
        System.out.println("   ╚═╝      ╚═════╝ ╚═╝  ╚═╝╚═╝   ╚═╝      ╚═╝   ");
        System.out.println();
    }

    /**
     * 获取模组配置实例
     * @return 配置实例，如果未初始化则返回 null
     */
    public static ChatPurityConfig getConfig() {
        return config;
    }

    /**
     * 获取聊天处理器实例
     * @return 聊天处理器实例，如果未初始化则返回 null
     */
    public static ChatHandler getChatHandler() {
        return chatHandler;
    }

    /**
     * 获取日志处理器实例
     * @return 日志处理器实例，如果未初始化则返回 null
     */
    public static LogHandler getLogHandler() {
        return logHandler;
    }

    /**
     * 获取警告处理器实例
     * @return 警告处理器实例，如果未初始化则返回 null
     */
    public static WarningHandler getWarningHandler() {
        return warningHandler;
    }

    /**
     * 获取防绕过处理器实例
     * @return 防绕过处理器实例，如果未初始化则返回 null
     */
    public static AntiBypassHandler getAntiBypassHandler() {
        return antiBypassHandler;
    }

    /**
     * 获取临时封禁处理器实例
     * @return 临时封禁处理器实例，如果未初始化则返回 null
     */
    public static TempBanHandler getTempBanHandler() {
        return tempBanHandler;
    }

    /**
     * 获取管理员通知处理器实例
     * @return 管理员通知处理器实例，如果未初始化则返回 null
     */
    public static AdminNotifyHandler getAdminNotifyHandler() {
        return adminNotifyHandler;
    }

    /**
     * 获取防刷屏处理器实例
     * @return 防刷屏处理器实例，如果未初始化则返回 null
     */
    public static AntiSpamHandler getAntiSpamHandler() {
        return antiSpamHandler;
    }

    /**
     * 获取 Minecraft 服务器实例
     * @return 服务器实例，如果服务器未启动则返回 null
     */
    public static MinecraftServer getServer() {
        return server;
    }
}