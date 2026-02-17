package com.chatpurity.handler;

import com.chatpurity.config.ChatPurityConfig;
import com.chatpurity.util.ChatPurityUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.BannedPlayerList;
import com.mojang.authlib.GameProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时封禁处理器
 * 
 * <p>管理玩家的违规记录和临时封禁：
 * <ul>
 *   <li>记录玩家违规次数</li>
 *   <li>达到阈值后执行临时封禁</li>
 *   <li>管理封禁列表（添加/移除/查询）</li>
 * </ul>
 * 
 * @see ChatPurityConfig 配置管理
 */
public class TempBanHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    private final ChatPurityConfig config;
    
    /** 玩家违规次数记录：UUID -> 违规次数 */
    private final Map<UUID, Integer> violationCounts = new ConcurrentHashMap<>();
    
    /**
     * 构造临时封禁处理器
     * @param config 配置实例
     */
    public TempBanHandler(ChatPurityConfig config) {
        this.config = config;
    }
    
    /**
     * 记录玩家违规
     * @param player 玩家
     * @param server 服务器实例
     * @return true 表示需要封禁
     */
    public boolean recordViolation(ServerPlayerEntity player, MinecraftServer server) {
        if (!config.isEnableTempBan() || player == null) {
            return false;
        }
        
        UUID uuid = player.getUuid();
        int currentViolations = violationCounts.getOrDefault(uuid, 0) + 1;
        violationCounts.put(uuid, currentViolations);
        
        // 检查是否达到封禁阈值
        if (currentViolations >= config.getTempBanViolations()) {
            // 执行封禁
            executeTempBan(player, server);
            // 重置违规次数
            violationCounts.remove(uuid);
            return true;
        }
        
        return false;
    }

    // 简化的方法，用于向后兼容
    public boolean shouldBan(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        int currentViolations = violationCounts.getOrDefault(uuid, 0);
        return currentViolations >= config.getTempBanViolations();
    }

    public void banPlayer(MinecraftServer server, ServerPlayerEntity player) {
        executeTempBan(player, server);
    }
    
    /**
     * 执行临时封禁
     * @param player 玩家
     * @param server 服务器实例
     */
    private void executeTempBan(ServerPlayerEntity player, MinecraftServer server) {
        if (player == null || server == null) {
            return;
        }
        
        // 解析封禁时长
        String durationStr = config.getTempBanDuration();
        long durationSeconds = parseDuration(durationStr);
        
        // 构建封禁消息
        String banMessage = config.getTempBanMessage()
            .replace("{duration}", ChatPurityUtils.formatDuration(durationSeconds))
            .replace("{reason}", "频繁违规");
        
        // 获取封禁列表
        BannedPlayerList banList = server.getPlayerManager().getUserBanList();
        
        // 计算封禁结束时间
        Date expires = new Date(System.currentTimeMillis() + durationSeconds * 1000);
        
        // 获取玩家 GameProfile
        GameProfile gameProfile = player.getGameProfile();
        
        // 创建 PlayerConfigEntry（用于 BannedPlayerEntry 构造）
        PlayerConfigEntry configEntry = new PlayerConfigEntry(gameProfile);
        
        // 创建封禁条目
        BannedPlayerEntry banEntry = new BannedPlayerEntry(
            configEntry,               // PlayerConfigEntry
            new Date(),               // 封禁时间
            "ChatPurity",             // 封禁者
            expires,                  // 过期时间
            "频繁违规"                 // 封禁原因
        );
        
        // 添加到封禁列表
        banList.add(banEntry);
        
        // 踢出玩家
        player.networkHandler.disconnect(Text.literal(banMessage));
        
        // 记录日志
        LOGGER.info("玩家 {} 已被临时封禁 {}，原因: 频繁违规", player.getName().getString(), ChatPurityUtils.formatDuration(durationSeconds));
    }
    
    /**
     * 解析时间字符串为秒数
     * 支持格式: 30s, 5m, 1h, 1d
     * @param duration 时间字符串
     * @return 秒数
     */
    private long parseDuration(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 1800; // 默认30分钟
        }
        
        try {
            duration = duration.trim().toLowerCase();
            long multiplier = 1;
            
            if (duration.endsWith("s")) {
                multiplier = 1;
                duration = duration.substring(0, duration.length() - 1);
            } else if (duration.endsWith("m")) {
                multiplier = 60;
                duration = duration.substring(0, duration.length() - 1);
            } else if (duration.endsWith("h")) {
                multiplier = 3600;
                duration = duration.substring(0, duration.length() - 1);
            } else if (duration.endsWith("d")) {
                multiplier = 86400;
                duration = duration.substring(0, duration.length() - 1);
            }
            
            return Long.parseLong(duration) * multiplier;
        } catch (NumberFormatException e) {
            return 1800; // 解析失败，默认30分钟
        }
    }
    
    /**
     * 解除玩家封禁
     * @param server 服务器实例
     * @param player 玩家
     * @return true 表示成功解封
     */
    public boolean unbanPlayer(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return false;
        }
        
        GameProfile gameProfile = player.getGameProfile();
        PlayerConfigEntry configEntry = new PlayerConfigEntry(gameProfile);
        
        BannedPlayerList banList = server.getPlayerManager().getUserBanList();
        
        // 检查是否在封禁列表中并移除
        if (banList.contains(configEntry)) {
            banList.remove(configEntry);
            LOGGER.info("已解除玩家 {} 的封禁", player.getName().getString());
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查玩家是否被封禁
     * @param server 服务器实例
     * @param player 玩家
     * @return true 表示已被封禁
     */
    public boolean isBanned(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return false;
        }
        
        GameProfile gameProfile = player.getGameProfile();
        PlayerConfigEntry configEntry = new PlayerConfigEntry(gameProfile);
        
        BannedPlayerList banList = server.getPlayerManager().getUserBanList();
        return banList.contains(configEntry);
    }
    
    /**
     * 清除玩家违规记录
     * @param player 玩家
     */
    public void clearViolations(ServerPlayerEntity player) {
        if (player != null) {
            violationCounts.remove(player.getUuid());
        }
    }
    
    /**
     * 获取玩家违规次数
     * @param player 玩家
     * @return 违规次数
     */
    public int getViolationCount(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }
        return violationCounts.getOrDefault(player.getUuid(), 0);
    }
}