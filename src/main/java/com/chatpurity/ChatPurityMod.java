package com.chatpurity;

import com.chatpurity.command.ChatPurityCommand;
import com.chatpurity.config.ChatPurityConfig;
import com.chatpurity.handler.ChatHandler;
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

import java.nio.file.Files;
import java.nio.file.Path;

public class ChatPurityMod implements ModInitializer {
    public static final String MOD_ID = "chatpurity";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    // 使用 volatile 确保多线程可见性
    // 注意：这些静态变量在单服务器环境下是安全的，但不支持多服务器实例
    private static volatile ChatPurityConfig config;
    private static volatile ChatHandler chatHandler;
    private static volatile MinecraftServer server;

    @Override
    public void onInitialize() {
        LOGGER.info("    ██████╗██╗  ██╗ █████╗ ████████╗██████╗ ██╗   ██╗██████╗ ██╗████████╗██╗   ██╗");
        LOGGER.info("   ██╔════╝██║  ██║██╔══██╗╚══██╔══╝██╔══██╗██║   ██║██╔══██╗██║╚══██╔══╝╚██╗ ██╔╝");
        LOGGER.info("   ██║     ███████║███████║   ██║   ██████╔╝██║   ██║██████╔╝██║   ██║    ╚████╔╝");
        LOGGER.info("   ██║     ██╔══██║██╔══██║   ██║   ██╔═══╝ ██║   ██║██╔══██╗██║   ██║     ╚██╔╝");
        LOGGER.info("   ╚██████╗██║  ██║██║  ██║   ██║   ██║     ╚██████╔╝██║  ██║██║   ██║      ██║");
        LOGGER.info("    ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ╚═╝      ╚═════╝ ╚═╝  ╚═╝╚═╝   ╚═╝      ╚═╝");
        LOGGER.info("ChatPurity initialized successfully!");

        // Register chat message event
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::onAllowChatMessage);

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ChatPurityCommand.register(dispatcher);
        });

        // Register server starting event to initialize config and handler
        ServerLifecycleEvents.SERVER_STARTING.register(minecraftServer -> {
            onServerStarting(minecraftServer);
        });

        // Register server stopping event to cleanup resources
        ServerLifecycleEvents.SERVER_STOPPING.register(minecraftServer -> {
            onServerStopping(minecraftServer);
        });
    }

    private void onServerStarting(MinecraftServer minecraftServer) {
        // 先完成所有初始化工作，再赋值给静态变量
        // 这样可以避免异常时出现部分初始化的状态不一致问题
        MinecraftServer tempServer = minecraftServer;
        ChatPurityConfig tempConfig = null;
        ChatHandler tempChatHandler = null;
        
        try {
            // Initialize configuration file
            // 配置文件路径：config/chatpurity/chatpurity.yml
            Path configDir = minecraftServer.getRunDirectory().toAbsolutePath().resolve("config").resolve("chatpurity");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                LOGGER.info("Created chatpurity config directory: {}", configDir);
            }
            Path configPath = configDir.resolve("chatpurity.yml");
            
            tempConfig = new ChatPurityConfig(configPath);
            tempChatHandler = new ChatHandler(tempConfig);
            
            // 所有初始化成功后再赋值给静态变量
            server = tempServer;
            config = tempConfig;
            chatHandler = tempChatHandler;
            
            LOGGER.info("ChatPurity configuration loaded successfully!");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize ChatPurity configuration", e);
            // 初始化失败，确保静态变量为 null
            server = null;
            config = null;
            chatHandler = null;
        }
    }

    private void onServerStopping(MinecraftServer minecraftServer) {
        if (config != null) {
            try {
                config.saveSync(); // 同步保存，确保数据写入
                config.shutdown(); // 关闭异步保存线程池
                LOGGER.info("ChatPurity configuration saved successfully!");
            } catch (Exception e) {
                LOGGER.error("Failed to save ChatPurity configuration", e);
            }
        }
        config = null;
        chatHandler = null;
        server = null;
    }

    private boolean onAllowChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params) {
        // 早期返回：检查必需的依赖是否初始化
        if (config == null || chatHandler == null || server == null) {
            LOGGER.warn("ChatPurity components not initialized, allowing message");
            return true;
        }
        
        // 检查总开关
        if (!config.isEnableFilter()) {
            return true;
        }
        
        // 安全检查：确保消息和发送者不为null
        if (message == null || sender == null) {
            LOGGER.warn("Null message or sender received, allowing message");
            return true;
        }
        
        try {
            Text content = message.getContent();
            if (content == null) {
                LOGGER.warn("Null message content received, allowing message");
                return true;
            }
            
            String originalText = content.getString();
            if (originalText == null) {
                LOGGER.warn("Null original text, allowing message");
                return true;
            }
            
            String processedText = chatHandler.processMessage(content, sender, server);
            
            if (processedText == null) {
                // 消息被屏蔽
                if (config.isNotifyBlocked()) {
                    String blockedMsg = config.getBlockedMessage();
                    if (blockedMsg != null && !blockedMsg.isEmpty()) {
                        sender.sendMessage(Text.literal(blockedMsg), true);
                    }
                }
                return false;
            }
            
            if (!originalText.equals(processedText)) {
                // 消息被替换，广播到聊天框显示
                String playerName = sender.getName().getString();
                if (playerName == null) {
                    playerName = "Unknown";
                }
                
                Text chatMessage = Text.literal("<" + playerName + "> " + processedText);
                // false = 发送到聊天框，true = 发送到动作栏
                server.getPlayerManager().broadcast(chatMessage, false);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Error processing chat message", e);
            // 发生异常时允许消息通过，避免影响正常聊天
            return true;
        }
        return true;
    }

    /**
     * 获取配置实例
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
     * 获取服务器实例
     * @return 服务器实例，如果未初始化则返回 null
     */
    public static MinecraftServer getServer() {
        return server;
    }
}