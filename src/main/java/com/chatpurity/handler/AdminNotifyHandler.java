package com.chatpurity.handler;

import com.chatpurity.config.ChatPurityConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 管理员通知处理器
 * 
 * <p>当玩家发送违规消息时通知在线管理员：
 * <ul>
 *   <li>可配置通知特定类型的违规</li>
 *   <li>支持自定义通知消息格式</li>
 *   <li>自动识别在线管理员</li>
 * </ul>
 * 
 * @see ChatPurityConfig 配置管理
 */
public class AdminNotifyHandler {
    private final ChatPurityConfig config;

    /**
     * 构造管理员通知处理器
     * @param config 配置实例
     */
    public AdminNotifyHandler(ChatPurityConfig config) {
        this.config = config;
    }
    
    /**
     * 通知在线管理员有玩家发送了违规消息
     * @param player 违规玩家
     * @param message 违规消息
     * @param reason 违规原因
     * @param server 服务器实例
     */
    public void notifyAdmins(ServerPlayerEntity player, String message, String reason, MinecraftServer server) {
            if (!config.isNotifyAdmins() || server == null) {
                return;
            }
            
            // 检查是否在需要通知的词汇列表中
            if (!shouldNotify(message, reason)) {
                return;
            }
            
            // 构建通知消息
            String playerName = player != null ? player.getName().getString() : "Unknown";
            String notifyMessage = config.getAdminNotifyMessage()
                .replace("{player}", playerName)
                .replace("{message}", message)
                .replace("{reason}", reason);
            
            // 发送给所有在线管理员
            Text notification = Text.literal(notifyMessage);
            for (ServerPlayerEntity admin : server.getPlayerManager().getPlayerList()) {
                if (isPlayerAdmin(admin, server)) {
                    admin.sendMessage(notification, false);
                }
            }
        }
    
    /**
     * 简化的方法，用于向后兼容（参数顺序调整）
     * @param server 服务器实例
     * @param player 违规玩家
     * @param message 违规消息
     * @param reason 违规原因
     */
    public void notifyAdmins(MinecraftServer server, ServerPlayerEntity player, String message, String reason) {
        notifyAdmins(player, message, reason, server);
    }
    
    /**
     * 检查是否应该通知管理员
     * @param message 违规消息
     * @param reason 违规原因
     * @return true 表示应该通知
     */
    private boolean shouldNotify(String message, String reason) {
        List<String> notifyWords = config.getNotifyWords();
        
        // 如果列表为空，通知所有违规
        if (notifyWords.isEmpty()) {
            return true;
        }
        
        // 检查消息是否包含需要通知的词汇
        for (String word : notifyWords) {
            if (config.isIgnoreCase()) {
                if (message.toLowerCase().contains(word.toLowerCase())) {
                    return true;
                }
            } else {
                if (message.contains(word)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 检查玩家是否为管理员
     * @param player 玩家
     * @param server 服务器实例
     * @return true 表示是管理员
     */
    private boolean isPlayerAdmin(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) {
            return false;
        }
        
        int requiredLevel = config.getBypassPermissionLevel();
        if (requiredLevel <= 0) {
            return false;
        }
        
        return server.getPlayerManager().isOperator(new net.minecraft.server.PlayerConfigEntry(player.getGameProfile()));
    }
}