# Changelog

## 1.1.0 — 2026-10-04

- Each premium account keeps one UUID on the server: renaming the account or switching `online-mode` no longer means starting over.
- Former names are protected: nobody can join with a name that a player of the server used before renaming.
- Premium skins and capes on offline-mode servers.
- Never stuck on Mojang: questions to Mojang give up after about 3 seconds; known players join with their usual UUID and last skin, new names with their offline UUID and the default skin.
- `/whitelist add` and `remove`, `/op`, `/deop`, `/ban` and `/pardon` act on all of a player's UUIDs; entries are completed when a player joins.
- New command `/offlinewhitelist info <player>`.

### 中文

- 每个正版账号在服务器上固定一个 UUID：改名、切换 `online-mode` 都不用再从头开始。
- 旧名字受保护：别人不能再用本服玩家改名前的名字进服。
- 离线服也能显示正版皮肤和披风。
- 不会被 Mojang 卡住：向 Mojang 查询最多约 3 秒；连不上时，认识的玩家照常用原来的 UUID 和上次的皮肤进服，新名字用离线 UUID 和默认皮肤。
- `/whitelist add` 和 `remove`、`/op`、`/deop`、`/ban`、`/pardon` 对一个玩家的所有 UUID 同时生效；玩家进服时自动补全他的条目。
- 新指令 `/offlinewhitelist info <玩家>`。

## 1.0.0 — 2026-10-02

- On servers with `online-mode=false`, `/whitelist add <name>` and `/whitelist remove <name>` use the offline UUID of exactly the name you type, instead of looking the name up at Mojang.

### 中文

- 在 `online-mode=false` 的服务器上，`/whitelist add <玩家名>` 和 `/whitelist remove <玩家名>` 直接使用所输入名字（原样）对应的离线 UUID，不再去 Mojang 查询。
