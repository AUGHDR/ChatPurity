package com.chatpurity.handler;

import com.chatpurity.config.ChatPurityConfig;
import com.chatpurity.util.ChatPurityUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 警告处理器
 * 
 * <p>管理玩家的警告机制：
 * <ul>
 *   <li>记录玩家警告次数</li>
 *   <li>达到阈值后执行惩罚（禁言/踢出/封禁）</li>
 *   <li>管理禁言状态和时长</li>
 * </ul>
 * 
 * @see ChatPurityConfig 配置管理
 */
public class WarningHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    private final ChatPurityConfig config;
    
    /** 玩家警告次数记录：UUID -> 警告次数 */
    private final Map<UUID, Integer> warningCounts = new ConcurrentHashMap<>();
    
    /** 禁言记录：UUID -> 禁言结束时间戳 */
    private final Map<UUID, Long> mutedPlayers = new ConcurrentHashMap<>();
    
    /** 默认禁言时长（秒） */
    private static final long DEFAULT_MUTE_DURATION = 300; // 5分钟
    
    /**
     * 构造警告处理器
     * @param config 配置实例
     */
    public WarningHandler(ChatPurityConfig config) {
        this.config = config;
    }
    
    /**
     * 检查玩家是否应该被警告
     * @param player 玩家
     * @return true 表示应该警告
     */
    public boolean shouldWarn(ServerPlayerEntity player) {
        return config.isEnableWarning() && player != null;
    }
    
    /**
     * 给玩家发送警告
     * @param player 玩家
     * @return true 表示需要执行惩罚
     */
    public boolean warnPlayer(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        
        UUID uuid = player.getUuid();
        int currentWarnings = warningCounts.getOrDefault(uuid, 0) + 1;
        warningCounts.put(uuid, currentWarnings);
        
        // 发送警告消息
        String warningMsg = config.getWarningMessage()
            .replace("{count}", String.valueOf(currentWarnings))
            .replace("{max}", String.valueOf(config.getMaxWarnings()));
        player.sendMessage(Text.literal(warningMsg), false);
        
        // 检查是否达到最大警告次数
        if (currentWarnings >= config.getMaxWarnings()) {
            // 重置警告次数
            warningCounts.remove(uuid);
            return true; // 需要执行惩罚
        }
        
        return false;
    }
    
    // 简化的方法，用于向后兼容
    public void addWarning(ServerPlayerEntity player) {
        if (config.isEnableWarning() && player != null) {
            boolean needsPunishment = warnPlayer(player);
            if (needsPunishment) {
                // 达到最大警告次数，执行惩罚（需要在服务器上下文中执行）
                // 这里只重置警告次数，实际惩罚由调用方决定
                warningCounts.remove(player.getUuid());
            }
        }
    }
    
    /**
     * 给玩家发送警告并检查是否需要执行惩罚
     * @param player 玩家
     * @param server 服务器实例
     * @return true 表示需要执行惩罚
     */
    public boolean addWarningAndCheckPunishment(ServerPlayerEntity player, MinecraftServer server) {
        if (!config.isEnableWarning() || player == null) {
            return false;
        }
        
        boolean needsPunishment = warnPlayer(player);
        if (needsPunishment) {
            // 重置警告次数
            warningCounts.remove(player.getUuid());
            // 执行惩罚
            executePunishment(player, server);
        }
        return needsPunishment;
    }    
    /**
     * 执行惩罚
     * @param player 玩家
     * @param server 服务器实例
     */
    public void executePunishment(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) {
            return;
        }
        
        String punishmentType = config.getWarningPunishment().toLowerCase();
        
        switch (punishmentType) {
            case "mute":
                // 禁言（使用防刷屏功能的惩罚时间）
                long muteDuration = getMuteDuration();
                mutePlayer(player, muteDuration);
                player.sendMessage(Text.literal("§c[ChatPurity] 您因多次违规被禁言 " + ChatPurityUtils.formatDuration(muteDuration)), false);
                break;
                
            case "kick":
                // 踢出服务器
                player.networkHandler.disconnect(Text.literal("§c[ChatPurity] 您因多次违规被踢出服务器"));
                break;

            case "tempban":
                // 临时封禁（交给 TempBanHandler 处理）
                player.networkHandler.disconnect(Text.literal("§c[ChatPurity] 您因多次违规被临时封禁"));
                break;
                
            default:
                // 默认禁言
                mutePlayer(player, DEFAULT_MUTE_DURATION);
                player.sendMessage(Text.literal("§c[ChatPurity] 您因多次违规被禁言"), false);
                break;
        }
    }
    
    /**
     * 禁言玩家
     * @param player 玩家
     * @param durationSeconds 禁言时长（秒）
     */
    public void mutePlayer(ServerPlayerEntity player, long durationSeconds) {
        if (player == null) {
            return;
        }
        
        UUID uuid = player.getUuid();
        long endTime = System.currentTimeMillis() + durationSeconds * 1000;
        mutedPlayers.put(uuid, endTime);
        
        LOGGER.info("玩家 {} 已被禁言 {}", player.getName().getString(), ChatPurityUtils.formatDuration(durationSeconds));
    }
    
    /**
     * 解除玩家禁言
     * @param player 玩家
     * @return true 表示成功解除
     */
    public boolean unmutePlayer(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        
        UUID uuid = player.getUuid();
        if (mutedPlayers.containsKey(uuid)) {
            mutedPlayers.remove(uuid);
            LOGGER.info("已解除玩家 {} 的禁言", player.getName().getString());
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查玩家是否被禁言
     * @param player 玩家
     * @return true 表示被禁言
     */
    public boolean isMuted(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        
        UUID uuid = player.getUuid();
        Long endTime = mutedPlayers.get(uuid);
        
        if (endTime == null) {
            return false;
        }
        
        // 检查禁言是否已过期
        if (System.currentTimeMillis() >= endTime) {
            // 移除过期记录
            mutedPlayers.remove(uuid);
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取玩家剩余禁言时间（秒）
     * @param player 玩家
     * @return 剩余秒数，0表示未禁言
     */
    public long getRemainingMuteTime(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }
        
        UUID uuid = player.getUuid();
        Long endTime = mutedPlayers.get(uuid);
        
        if (endTime == null) {
            return 0;
        }
        
        long remaining = (endTime - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }
    
    /**
     * 获取禁言时长配置
     * @return 禁言时长（秒）
     */
    private long getMuteDuration() {
        // 使用防刷屏惩罚时间作为禁言时长
        int punishmentTime = config.getSpamPunishmentTime();
        if (punishmentTime > 0) {
            return punishmentTime;
        }
        return DEFAULT_MUTE_DURATION;
    }
    
    /**
     * 清除玩家警告
     * @param player 玩家
     */
    public void clearWarnings(ServerPlayerEntity player) {
        if (player != null) {
            warningCounts.remove(player.getUuid());
        }
    }
    
    /**
     * 获取玩家警告次数
     * @param player 玩家
     * @return 警告次数
     */
    public int getWarningCount(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }
        return warningCounts.getOrDefault(player.getUuid(), 0);
    }
}