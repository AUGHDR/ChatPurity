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
 * 防刷屏处理器
 */
public class AntiSpamHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    private final ChatPurityConfig config;
    private MinecraftServer server;
    
    // 玩家最后一条消息时间：UUID -> 时间戳
    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    
    // 玩家最后一条消息内容：UUID -> 消息内容
    private final Map<UUID, String> lastMessageContent = new ConcurrentHashMap<>();
    
    // 玩家在时间窗口内的消息计数：UUID -> 消息数量
    private final Map<UUID, Integer> messageCount = new ConcurrentHashMap<>();
    
    // 玩家时间窗口开始时间：UUID -> 时间戳
    private final Map<UUID, Long> timeWindowStart = new ConcurrentHashMap<>();
    
    // 禁言记录：UUID -> 禁言结束时间戳
    private final Map<UUID, Long> mutedPlayers = new ConcurrentHashMap<>();
    
    public AntiSpamHandler(ChatPurityConfig config) {
        this.config = config;
    }
    
    /**
     * 设置服务器实例（用于获取玩家名称）
     * @param server 服务器实例
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }
    
    /**
     * 检查消息是否应该被阻止（刷屏检测）
     * @param player 玩家
     * @param message 消息内容
     * @return true 表示应该阻止
     */
    public boolean shouldBlock(ServerPlayerEntity player, String message) {
        if (!config.isEnableAntiSpam() || player == null) {
            return false;
        }
        
        // 检查玩家是否被禁言
        if (isMuted(player)) {
            long remaining = getRemainingMuteTime(player);
            player.sendMessage(Text.literal("§c[ChatPurity] 您已被禁言，请等待 " + ChatPurityUtils.formatDuration(remaining) + " 后再试"), true);
            return true;
        }
        
        String mode = config.getAntiSpamMode();
        boolean blocked = false;
        
        // 检查相同消息冷却
        if (mode.equals("same") || mode.equals("both")) {
            if (checkSameMessageCooldown(player, message)) {
                blocked = true;
            }
        }
        
        // 检查快速消息限制
        if (mode.equals("fast") || mode.equals("both")) {
            if (checkFastMessageLimit(player)) {
                blocked = true;
            }
        }
        
        return blocked;
    }
    
    /**
     * 检查相同消息冷却
     */
    private boolean checkSameMessageCooldown(ServerPlayerEntity player, String message) {
        UUID uuid = player.getUuid();
        long currentTime = System.currentTimeMillis();
        long cooldownMs = config.getSpamCooldownSeconds() * 1000L;
        
        String lastMessage = lastMessageContent.get(uuid);
        Long lastTime = lastMessageTime.get(uuid);
        
        if (lastMessage != null && lastTime != null) {
            // 检查消息是否相同
            if (lastMessage.equals(message)) {
                long timeDiff = currentTime - lastTime;
                if (timeDiff < cooldownMs) {
                    long remaining = (cooldownMs - timeDiff) / 1000;
                    player.sendMessage(Text.literal("§c[ChatPurity] 请勿发送重复消息，请等待 " + remaining + " 秒后再试"), true);
                    return true;
                }
            }
        }
        
        // 更新最后一条消息
        lastMessageTime.put(uuid, currentTime);
        lastMessageContent.put(uuid, message);
        return false;
    }
    
    /**
     * 检查快速消息限制
     */
    private boolean checkFastMessageLimit(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        long currentTime = System.currentTimeMillis();
        long windowMs = config.getSpamTimeWindow() * 1000L;
        int maxMessages = config.getSpamMaxMessages();
        
        // 获取或初始化时间窗口
        Long windowStart = timeWindowStart.get(uuid);
        if (windowStart == null || currentTime - windowStart >= windowMs) {
            // 新的时间窗口
            timeWindowStart.put(uuid, currentTime);
            messageCount.put(uuid, 1);
            return false;
        }
        
        // 增加消息计数
        int count = messageCount.getOrDefault(uuid, 0) + 1;
        messageCount.put(uuid, count);
        
        if (count > maxMessages) {
            // 触发刷屏限制
            int punishmentTime = config.getSpamPunishmentTime();
            
            if (punishmentTime > 0) {
                // 禁言
                mutePlayer(player, punishmentTime);
                player.sendMessage(Text.literal(config.getSpamPunishmentMessage()
                    .replace("{time}", String.valueOf(punishmentTime))), true);
            } else {
                // 只阻止当前消息
                player.sendMessage(Text.literal(config.getSpamMessage()
                    .replace("{seconds}", String.valueOf(config.getSpamCooldownSeconds()))), true);
            }
            
            // 重置计数
            messageCount.put(uuid, 0);
            timeWindowStart.remove(uuid);
            return true;
        }
        
        return false;
    }
    
    /**
     * 禁言玩家
     */
    public void mutePlayer(ServerPlayerEntity player, int durationSeconds) {
        if (player == null) {
            return;
        }
        
        UUID uuid = player.getUuid();
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;
        mutedPlayers.put(uuid, endTime);
        
        LOGGER.info("玩家 {} 因刷屏被禁言 {}", player.getName().getString(), ChatPurityUtils.formatDuration(durationSeconds));
    }
    
    /**
     * 解除玩家禁言
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
     * 清除玩家刷屏记录
     */
    public void clearRecords(ServerPlayerEntity player) {
        if (player != null) {
            UUID uuid = player.getUuid();
            lastMessageTime.remove(uuid);
            lastMessageContent.remove(uuid);
            messageCount.remove(uuid);
            timeWindowStart.remove(uuid);
        }
    }
    
    /**
     * 获取当前禁言列表
     * @return 玩家名 -> 禁言结束时间的映射
     */
    public Map<String, Long> getMutedPlayers() {
        Map<String, Long> result = new HashMap<>();
        long currentTime = System.currentTimeMillis();
        
        for (Map.Entry<UUID, Long> entry : mutedPlayers.entrySet()) {
            if (currentTime < entry.getValue()) {
                String playerName = getPlayerName(entry.getKey());
                result.put(playerName, entry.getValue());
            }
        }
        
        return result;
    }
    
    /**
     * 根据 UUID 获取玩家名称
     * @param uuid 玩家 UUID
     * @return 玩家名称，如果无法获取则返回 UUID 字符串
     */
    private String getPlayerName(UUID uuid) {
        if (server != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                return player.getName().getString();
            }
        }
        // 无法获取玩家名时返回简短的 UUID
        return uuid.toString().substring(0, 8) + "...";
    }
}