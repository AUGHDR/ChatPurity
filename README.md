ChatPurity 聊天过滤

一个 ⸮！虽！？ 的服务器聊天管理的MOD

白名单

白名单支持精确匹配、包含匹配、正则表达式，还能把敏感词替换成三星堆,或者像服务器把L变成love



防绕过

颜色代码检测 §r

Unicode变体 吿告这种混淆視

拼音混合 guang告

谐音字检测 搞高这种同音字

["all"] 一键匹配所有谐音字 

还有:

防刷屏

警告与惩罚:警告三次就禁言，屡教不改直接ban(可改)

管理员通知



配置

配置文件在 config/chatpurity/ 下面，改完输入 /chatpurity reload 热重载，不用重启服务器



指令

所有命令需要OP权限



基础命令

/chatpurity help - 显示帮助

/chatpurity reload - 重载配置

/chatpurity list - 列出所有配置



白名单

/chatpurity whitelist add <词> - 添加白名单词

/chatpurity whitelist remove <词> - 移除白名单词

/chatpurity whitelist list - 查看白名单



黑名单

/chatpurity blacklist add <词> - 添加黑名单词

/chatpurity blacklist remove <词> - 移除黑名单词

/chatpurity blacklist list - 查看黑名单



单词黑名单:如果发现一句话只有这一种词或字就屏蔽(可设置阈值)

/chatpurity wordblacklist add <词> - 添加单词黑名单

/chatpurity wordblacklist remove <词> - 移除单词黑名单

/chatpurity wordblacklist list - 查看单词黑名单



转换词

/chatpurity conversion add <原词> <新词> - 添加转换词

/chatpurity conversion remove <原词> - 移除转换词

/chatpurity conversion list - 查看转换词



禁言管理

/chatpurity mute <玩家> [时长(秒)] - 禁言玩家(默认5分钟)

/chatpurity unmute <玩家> - 解除禁言

/chatpurity mutelist - 查看禁言列表



解封

/chatpurity unban <玩家> - 解除临时解封



设置

/chatpurity set basic enableFilter <true/false> - 开关过滤

/chatpurity set basic enableReleaseCompliant <true/false> - 合规释放模式

/chatpurity set whitelist enable <true/false> - 开关白名单

/chatpurity set blacklist enable <true/false> - 开关黑名单

/chatpurity set mixedblacklist enable <true/false> - 开关夹杂词黑名单

/chatpurity set log enable <true/false> - 开关日志

/chatpurity set warning enable <true/false> - 开关警告

/chatpurity set antibypass enable <true/false> - 开关防绕过

/chatpurity set tempban enable <true/false> - 开关临时封禁

/chatpurity set notify admins <true/false> - 开关管理员通知

