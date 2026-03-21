package com.chatpurity.util;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * 权限工具类
 * 
 * <p>提供跨版本兼容的权限检查方法。
 * 在 Minecraft 1.21 中，权限检查使用 ServerCommandSource.hasPermissionLevel()。
 * 在 Minecraft 1.21.1+ 中，使用新的 Permission API。
 * 
 * <p>此类通过反射自动检测并使用正确的 API。
 */
public final class PermissionUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger("chatpurity");
    
    // 缓存反射方法以提高性能
    private static Method hasPermissionLevelMethod = null;
    private static Method getPermissionsMethod = null;
    private static Class<?> permissionLevelClass = null;
    private static boolean initialized = false;
    private static boolean useNewApi = false;
    
    private PermissionUtils() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }
    
    /**
     * 初始化反射方法缓存
     */
    private static void initIfNeeded() {
        if (initialized) return;
        initialized = true;
        
        try {
            // 尝试获取 1.21.1+ 的新 Permission API
            permissionLevelClass = Class.forName("net.minecraft.command.permission.PermissionLevel");
            getPermissionsMethod = ServerCommandSource.class.getMethod("getPermissions");
            useNewApi = true;
            LOGGER.debug("Using new Permission API (1.21.1+)");
        } catch (Exception e) {
            // 回退到旧 API (1.21)
            try {
                hasPermissionLevelMethod = ServerCommandSource.class.getMethod("hasPermissionLevel", int.class);
                useNewApi = false;
                LOGGER.debug("Using legacy hasPermissionLevel API (1.21)");
            } catch (NoSuchMethodException ex) {
                LOGGER.error("Failed to find any permission check method!", ex);
            }
        }
    }
    
    /**
     * 检查玩家是否具有指定的权限等级
     * 
     * @param player 玩家
     * @param level 权限等级 (1-4)
     * @return 如果玩家具有该等级或更高的权限则返回 true
     */
    public static boolean hasPermissionLevel(ServerPlayerEntity player, int level) {
        if (player == null) {
            return false;
        }
        return hasPermissionLevel(player.getCommandSource(), level);
    }
    
    /**
     * 检查命令源是否具有指定的权限等级
     * 
     * @param source 命令源
     * @param level 权限等级 (1-4)
     * @return 如果命令源具有该等级或更高的权限则返回 true
     */
    public static boolean hasPermissionLevel(ServerCommandSource source, int level) {
        if (source == null) {
            return false;
        }
        
        initIfNeeded();
        
        try {
            if (useNewApi && getPermissionsMethod != null && permissionLevelClass != null) {
                // 使用 1.21.1+ 的 Permission API
                // 获取 Permissions 对象
                Object permissions = getPermissionsMethod.invoke(source);
                if (permissions == null) {
                    return false;
                }
                
                // 获取 PermissionLevel 枚举值
                Object[] enumConstants = permissionLevelClass.getEnumConstants();
                if (enumConstants == null || enumConstants.length == 0) {
                    return false;
                }
                
                // 查找对应的 PermissionLevel (枚举值按 ordinal 排列: ALL_PLAYERS=0, MODERATORS=1, GAME_MASTERS=2, ADMIN=3, OWNER=4)
                // 权限等级 1-4 对应 MODERATORS 到 OWNER
                int enumIndex = level; // PermissionLevel 枚举索引
                if (enumIndex < 0 || enumIndex >= enumConstants.length) {
                    return false;
                }
                Object permissionLevel = enumConstants[enumIndex];
                
                // 调用 Permissions.hasPermission(PermissionLevel) 方法
                Method hasPermissionMethod = permissions.getClass().getMethod("hasPermission", permissionLevelClass);
                return (Boolean) hasPermissionMethod.invoke(permissions, permissionLevel);
                
            } else if (hasPermissionLevelMethod != null) {
                // 使用 1.21 的 hasPermissionLevel 方法
                return (Boolean) hasPermissionLevelMethod.invoke(source, level);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to check permission level", e);
        }
        
        return false;
    }
    
    /**
     * 获取玩家的权限等级
     * 
     * @param player 玩家
     * @return 权限等级 (0-4)，非 OP 玩家返回 0
     */
    public static int getPermissionLevel(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }
        
        // 检查各个权限等级（从高到低）
        for (int level = 4; level >= 1; level--) {
            if (hasPermissionLevel(player, level)) {
                return level;
            }
        }
        
        return 0;
    }
}