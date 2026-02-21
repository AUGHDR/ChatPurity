Modrinth:https://modrinth.com/mod/chatpurity
MC百科:https://www.mcmod.cn/class/24956.html
ChatPurity 聊天过滤

一个专为中国 MC 社区设计的服务器聊天净化模组，超多配置、超强检测，包括白名单、黑名单、谐音、单字检测、替换等功能。

白名单支持精确匹配、包含匹配、正则表达式，还能把敏感词替换成三星堆，或者像某服务器把 L 变成 love。

还有:

词组检测；

拼音检测：guang 告；

谐音字检测：镐高这种同音字;

防刷屏；

等等

模组配置

配置文件在 config/chatpurity/ 下面，改完输入 /chatpurity reload 热重载，不用重启服务器。

命令

所有命令需要 OP 权限。

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
