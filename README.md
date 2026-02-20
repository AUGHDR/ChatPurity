ChatPurity

专为中国 Minecraft 社区设计的服务器聊天净化模组，支持多种检测模式和丰富的配置选项。

功能特性

白名单 - 精确匹配、包含匹配、正则表达式、内容替换
黑名单 - 包含检测、精确匹配、同音词检测、组合词检测、OP权限控制
替换词 - 默认替换、精确替换、同音词替换、组合词替换
防绕过 - 颜色代码、Unicode变体、拼音混合、谐音字检测
防刷屏 - 禁言、踢出、封禁、警告机制
管理员通知 - 违规消息推送

配置

配置文件位于 config/chatpurity/，修改后使用 /chatpurity reload 热重载。

config/chatpurity/
  chatpurity.yml              主配置
  chatpurity-homophone.yml    谐音字库

详细配置示例请参考 chatpurity-example.yml。

指令

基础命令

/chatpurity help 显示帮助
/chatpurity reload 重载配置

白名单管理

/chatpurity whitelist add <词> 添加白名单
/chatpurity whitelist remove <词> 移除白名单
/chatpurity whitelist list 列出白名单

黑名单管理

/chatpurity blacklist add <词> [模式] 添加黑名单
/chatpurity blacklist remove <词> 移除黑名单
/chatpurity blacklist list 列出黑名单

检测模式

默认：包含检测，只要包含指定词就屏蔽
homophone：同音词检测，检测同音词、同音字
pinyin_abbr：拼音缩写检测，检测拼音首字母
pinyin_full：完整拼音检测，检测完整拼音
exact_match：精确匹配，只检测完全相同的词或重复词

OP权限控制

op1：OP1及以下会被屏蔽，OP2/3/4可发送
op2：OP2及以下会被屏蔽，OP3/4可发送
op3：OP3及以下会被屏蔽，OP4可发送
op4：所有人都会被屏蔽

模式可组合使用，用冒号分隔，如 homophone:pinyin_abbr:pinyin_full

替换词管理

/chatpurity replacement add <原词> <替换词> [模式] 添加替换规则
/chatpurity replacement remove <原词> 移除替换规则
/chatpurity replacement list 列出替换规则

模式与黑名单相同

禁言管理

/chatpurity mute add <玩家> <时间> 禁言玩家
/chatpurity mute remove <玩家> 解除禁言
/chatpurity mute list 列出被禁言玩家

时间单位：s(秒)、min(分钟)、h(小时)、d(天)、-1(永久)


许可证

MIT License
