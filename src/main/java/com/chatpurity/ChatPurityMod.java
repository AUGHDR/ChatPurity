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
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public class ChatPurityMod implements ModInitializer {
    public static final String MOD_ID = "chatpurity";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static final AtomicReference<ChatPurityConfig> config = new AtomicReference<>();
    private static final AtomicReference<ChatHandler> chatHandler = new AtomicReference<>();
    private static final AtomicReference<MinecraftServer> server = new AtomicReference<>();

    @Override
    public void onInitialize() {
        LOGGER.info("    ██████╗██╗  ██╗ █████╗ ████████╗██████╗ ██╗   ██╗██████╗ ██╗████████╗██╗   ██╗");
        LOGGER.info("   ██╔════╝██║  ██║██╔══██╗╚══██╔══╝██╔══██╗██║   ██║██╔══██╗██║╚══██╔══╝╚██╗ ██╔╝");
        LOGGER.info("   ██║     ███████║███████║   ██║   ██████╔╝██║   ██║██████╔╝██║   ██║    ╚████╔╝");
        LOGGER.info("   ██║     ██╔══██║██╔══██║   ██║   ██╔═══╝ ██║   ██║██╔══██╗██║   ██║     ╚██╔╝");
        LOGGER.info("   ╚██████╗██║  ██║██║  ██║   ██║   ██║     ╚██████╔╝██║  ██║██║   ██║      ██║");
        LOGGER.info("    ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝      ╚═════╝ ╚═╝  ╚═╝╚═╝   ╚═╝      ╚═╝");
        LOGGER.info("ChatPurity initialized successfully!");

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::onAllowChatMessage);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ChatPurityCommand.register(dispatcher);
        });
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
    }

    private void onServerStarting(MinecraftServer minecraftServer) {
        try {
            Path runDir = getRunDirectory(minecraftServer);
            Path configDir = runDir.resolve("config").resolve("chatpurity");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                LOGGER.info("Created chatpurity config directory: {}", configDir);
            }
            Path configPath = configDir.resolve("chatpurity.yml");
            
            ChatPurityConfig tempConfig = new ChatPurityConfig(configPath);
            ChatHandler tempChatHandler = new ChatHandler(tempConfig);
            
            server.set(minecraftServer);
            config.set(tempConfig);
            chatHandler.set(tempChatHandler);
            
            LOGGER.info("ChatPurity configuration loaded successfully!");
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to read/write ChatPurity configuration files", e);
            clearState();
        } catch (RuntimeException e) {
            LOGGER.error("Unexpected error during ChatPurity initialization", e);
            clearState();
        }
    }
    
    private void clearState() {
        server.set(null);
        config.set(null);
        chatHandler.set(null);
    }

    private Path getRunDirectory(MinecraftServer server) {
        Object runDir = server.getRunDirectory();
        if (runDir instanceof Path) {
            return (Path) runDir;
        } else if (runDir instanceof File) {
            return ((File) runDir).toPath();
        }
        throw new IllegalStateException("Unknown run directory type: " + runDir.getClass());
    }

    private void onServerStopping(MinecraftServer minecraftServer) {
        ChatPurityConfig currentConfig = config.get();
        if (currentConfig != null) {
            try {
                currentConfig.saveSync();
                currentConfig.shutdown();
                LOGGER.info("ChatPurity configuration saved successfully!");
            } catch (Exception e) {
                LOGGER.error("Failed to save ChatPurity configuration", e);
            }
        }
        clearState();
    }

    private boolean onAllowChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params) {
        ChatPurityConfig currentConfig = config.get();
        ChatHandler currentHandler = chatHandler.get();
        MinecraftServer currentServer = server.get();
        
        if (currentConfig == null || currentHandler == null || currentServer == null) {
            LOGGER.warn("ChatPurity components not initialized, allowing message");
            return true;
        }
        
        if (!currentConfig.isEnableFilter()) {
            return true;
        }
        
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
            
            String processedText = currentHandler.processMessage(content, sender, currentServer);
            
            if (processedText == null) {
                if (currentConfig.isNotifyBlocked()) {
                    String blockedMsg = currentConfig.getBlockedMessage();
                    if (blockedMsg != null && !blockedMsg.isEmpty()) {
                        sender.sendMessage(Text.literal(blockedMsg), true);
                    }
                }
                return false;
            }
            
            if (!originalText.equals(processedText)) {
                String playerName = sender.getName().getString();
                if (playerName == null) {
                    playerName = "Unknown";
                }
                
                Text chatMessage = Text.literal("<" + playerName + "> " + processedText);
                currentServer.getPlayerManager().broadcast(chatMessage, false);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Error processing chat message", e);
            return true;
        }
        return true;
    }

    public static ChatPurityConfig getConfig() {
        return config.get();
    }

    public static ChatHandler getChatHandler() {
        return chatHandler.get();
    }

    public static MinecraftServer getServer() {
        return server.get();
    }
}