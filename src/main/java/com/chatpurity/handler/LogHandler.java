package com.chatpurity.handler;

import com.chatpurity.config.ChatPurityConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志处理器
 * 
 * <p>管理违规消息的日志记录：
 * <ul>
 *   <li>记录被屏蔽或替换的消息</li>
 *   <li>支持自定义日志格式</li>
 *   <li>自动创建日志目录</li>
 *   <li>支持相对于服务器运行目录的路径</li>
 * </ul>
 * 
 * @see ChatPurityConfig 配置管理
 */
public class LogHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    private final ChatPurityConfig config;
    private Path serverRunDirectory;

    /**
     * 构造日志处理器
     * @param config 配置实例
     */
    public LogHandler(ChatPurityConfig config) {
        this.config = config;
    }
    
    /**
     * 设置服务器运行目录
     * @param server Minecraft 服务器实例
     */
    public void setServer(MinecraftServer server) {
        if (server != null) {
            this.serverRunDirectory = server.getRunDirectory();
        }
    }
    
    /**
     * 记录被屏蔽或替换的消息
     * @param player 玩家
     * @param message 原始消息
     * @param type 操作类型（blocked/replaced）
     * @param reason 原因
     */
    public void logMessage(ServerPlayerEntity player, String message, String type, String reason) {
        if (!config.isEnableLog()) {
            return;
        }
        
        try {
            // 构建日志内容
            String logEntry = formatLogEntry(player, message, type, reason);
            
            // 解析日志路径（相对于服务器运行目录）
            Path logPath = resolveLogPath();
            
            // 确保日志目录存在
            Path parentDir = logPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logPath.toFile(), true))) {
                writer.write(logEntry);
                writer.newLine();
            }
        } catch (IOException e) {
            LOGGER.error("写入日志失败: {}", e.getMessage());
        }
    }
    
    /**
     * 解析日志路径
     * <p>支持：
     * <ul>
     *   <li>绝对路径：直接使用</li>
     *   <li>相对路径：相对于服务器运行目录</li>
     * </ul>
     * 
     * @return 解析后的日志文件路径
     */
    private Path resolveLogPath() {
        String logPathStr = config.getLogPath();
        Path logPath = Paths.get(logPathStr);
        
        // 如果是绝对路径，直接使用
        if (logPath.isAbsolute()) {
            return logPath;
        }
        
        // 相对路径，基于服务器运行目录
        if (serverRunDirectory != null) {
            return serverRunDirectory.resolve(logPathStr);
        }
        
        // 没有服务器目录，使用当前工作目录
        return Paths.get(System.getProperty("user.dir")).resolve(logPathStr);
    }

    // 简化的方法，用于向后兼容
    public void log(ServerPlayerEntity player, String message, String type, String reason) {
        logMessage(player, message, type, reason);
    }
    
    /**
     * 格式化日志条目
     * @param player 玩家
     * @param message 原始消息
     * @param type 操作类型
     * @param reason 原因
     * @return 格式化后的日志字符串
     */
    private String formatLogEntry(ServerPlayerEntity player, String message, String type, String reason) {
        String format = config.getLogFormat();
        String result = format;
        
        if (config.isLogTimestamp()) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            result = result.replace("{timestamp}", timestamp);
        } else {
            result = result.replace("{timestamp}", "");
        }
        
        if (config.isLogPlayerName() && player != null) {
            result = result.replace("{player}", player.getName().getString());
        } else {
            result = result.replace("{player}", "Unknown");
        }
        
        result = result.replace("{message}", message);
        result = result.replace("{type}", type);
        result = result.replace("{reason}", reason);
        
        return result;
    }
}