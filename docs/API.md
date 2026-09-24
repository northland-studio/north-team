# NorthTeam API 契约 v1

官网（`https://xuanjian.top`）与 Paper 插件（NorthTeam）之间的唯一接口约定。
**改这个文件必须同步改两边实现。**

## 0. 概念

| 概念 | 说明 | 例子 |
|---|---|---|
| 队伍配置（config） | 一场活动中所有队伍信息的集合 | 周年庆大逃杀 |
| 队伍单元（unit） | 配置里的单个队伍 | 黄队 |
| 成员（member） | 队伍里的玩家，**以游戏 ID（离线模式玩家名）为准** | `Morzane123` |

`/nt apply <ID>` 里的 ID = **配置 ID**（不是队伍单元）。

## 1. 认证

| 接口组 | 认证 |
|---|---|
| 公示读（`/api/team/public*`） | 无认证，任意人可读，**只返回 `is_public=1` 的配置** |
| 插件读/回传（`/api/team/list`、`/api/team/export/:id`、`POST /api/team/import`） | 请求头 `X-Server-Key: <key>`，密钥取自官网 `mod_servers.server_key`（与 `/api/mod/*` 同一套） |
| 管理写（`/api/team/configs*`） | 官网 JWT `Authorization: Bearer <token>` + 管理员身份 |

错误统一为 `{ "error": "人类可读的原因" }`，HTTP 状态码分别 400/401/403/404/409/500。

## 2. 数据结构（JSON）

### 2.1 完整配置（export / public 详情 / 管理读写共用同一形状）

```json
{
  "schema": 1,
  "id": 12,
  "name": "周年庆大逃杀",
  "description": "国庆活动，四人一队，共四队",
  "event_date": "2026-10-01",
  "is_public": true,
  "version": "2026-09-19 18:32:10",
  "scoreboard": {
    "enabled": true,
    "objective": "nt_teams",
    "display_name": "<gold>队伍</gold>",
    "position": "sidebar",
    "score_mode": "member_count"
  },
  "units": [
    {
      "key": "yellow",
      "display_name": "黄队",
      "color": "yellow",
      "prefix": "<yellow>[黄]</yellow> ",
      "suffix": "",
      "friendly_fire": false,
      "see_friendly_invisibles": true,
      "nametag_visibility": "always",
      "death_message_visibility": "always",
      "collision_rule": "always",
      "sort_order": 1,
      "members": ["Morzane123", "brichir"]
    }
  ]
}
```

字段约束：

| 字段 | 约束 |
|---|---|
| `key` | `[a-z0-9_]{1,16}`，配置内唯一；插件用它做 scoreboard team 名（前缀 `nt_`） |
| `display_name` | 1~32 字符，MiniMessage 允许 |
| `color` | 16 种原版颜色名之一：`black dark_blue dark_green dark_aqua dark_red dark_purple gold gray dark_gray blue green aqua red light_purple yellow white` |
| `prefix` / `suffix` | MiniMessage 字符串，长度 ≤ 64（含标签）；允许 `&` 传统颜色码，插件侧统一转换 |
| `friendly_fire` | bool，对应 `/team modify <t> friendlyFire` |
| `see_friendly_invisibles` | bool，对应 `seeFriendlyInvisibles` |
| `nametag_visibility` | `always` \| `hideForOtherTeams` \| `hideForOwnTeam` \| `never` |
| `death_message_visibility` | 同上 |
| `collision_rule` | `always` \| `pushOtherTeams` \| `pushOwnTeam` \| `never` |
| `members` | 游戏 ID 字符串数组（离线模式名，大小写不敏感去重）；单配置内一个玩家只能在一个队伍，服务端负责校验 |
| `scoreboard.score_mode` | `member_count`（每队一行，分数=人数）\| `fixed`（分数取 `scoreboard.unit_scores[key]`，本期不使用，保留） |
| `scoreboard.position` | `sidebar` \| `list` \| `below_name` |
| `version` | 官网写入时间戳（`YYYY-MM-DD HH:MM:SS`，UTC+8 本地时间）；插件用它做缓存对比，**相同即跳过应用**（除非 `/nt apply --force`） |

## 3. 接口清单

### 3.1 `GET /api/team/list`（X-Server-Key）
```json
{ "count": 2, "configs": [
  { "id": 12, "name": "周年庆大逃杀", "event_date": "2026-10-01", "is_public": true,
    "version": "2026-09-19 18:32:10", "unit_count": 4, "member_count": 40 }
] }
```

### 3.2 `GET /api/team/export/:id`（X-Server-Key）
返回 2.1 的完整配置；未公示也返回（插件通道不受 `is_public` 限制）。404 = 配置不存在。

### 3.3 `POST /api/team/import`（X-Server-Key）
插件把服务器当前队伍采集回官网，**新建**一条配置（默认 `is_public=0`，可后续在管理页公示）。

请求体 = 2.1 去掉 `id`/`version`，`name` 可用 query/字段给出（默认 `服务器采集 <时间>`）：
```json
{ "schema": 1, "name": "115 现场采集", "description": "...", "event_date": null,
  "scoreboard": { "enabled": false, "objective": "nt_teams", "display_name": "<gold>队伍</gold>", "position": "sidebar", "score_mode": "member_count" },
  "units": [ { "key": "yellow", "display_name": "黄队", "color": "yellow", "prefix": "<yellow>[黄]</yellow> ", "suffix": "",
               "friendly_fire": false, "see_friendly_invisibles": true, "nametag_visibility": "always",
               "death_message_visibility": "always", "collision_rule": "always", "sort_order": 1,
               "members": ["Morzane123"] } ] }
```
响应：`{ "id": 13, "name": "115 现场采集", "unit_count": 4, "member_count": 40, "message": "已保存，默认不公示" }`

### 3.4 `GET /api/team/public`（无认证）
```json
{ "count": 1, "configs": [
  { "id": 12, "name": "周年庆大逃杀", "event_date": "2026-10-01", "version": "2026-09-19 18:32:10",
    "unit_count": 4, "member_count": 40, "teams": [ { "key": "yellow", "display_name": "黄队", "color": "yellow", "member_count": 2 } ] }
] }
```

### 3.5 `GET /api/team/public/:id`（无认证）
返回 2.1 完整配置（含成员名单）；`is_public != 1` 时 404。

### 3.6 管理接口（JWT + 管理员）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/team/configs` | 全量列表（含未公示），带 `unit_count`/`member_count` |
| GET | `/api/team/configs/:id` | 完整配置（编辑用） |
| POST | `/api/team/configs` | 新建，body = 2.1 去掉 `id`/`version`；返回 `{ "id": ... }` |
| PUT | `/api/team/configs/:id` | 全量覆盖更新（units/members 一起替换），刷新 `version` |
| DELETE | `/api/team/configs/:id` | 删除（级联删 units/members） |

校验失败返回 400 + 具体原因（例如 `玩家 Morzane123 同时出现在 黄队 和 红队`、`队伍 key 重复: yellow`）。

## 4. 插件侧行为约定（`/nt`）

| 命令 | 权限 | 行为 |
|---|---|---|
| `/nt list` | `northteam.admin`（默认 OP） | 拉 `3.1`，聊天栏列出 ID / 名称 / 日期 / 队伍数 / 人数 / 是否公示 / 是否当前已应用 |
| `/nt info <id>` | 同上 | 拉 `3.2`，列出每个队伍的颜色、前缀、规则、成员数，并标出与当前服务器状态的差异 |
| `/nt apply <id> [--dry-run] [--force]` | 同上 | 拉 `3.2` → **全量重建**：删除配置外的受管队伍 → 按 `key` 建/更新队伍 → 清空并重写成员 → 写 `state.json`；`--dry-run` 只打印将要做的变更 |
| `/nt import [名称]` | 同上 | 采集服务器全部 scoreboard team（含前缀/颜色/规则/成员）→ `POST 3.3`，返回新配置 ID |
| `/nt reload` | 同上 | 重读 `config.yml`（API 地址、密钥、开关） |
| `/nt status` | 同上 | 显示当前已应用配置、缓存文件、上次同步时间、API 可达性 |

应用语义（v1 已确认）：
1. **全量重建**：配置里没有的队伍会被删除（`apply.wipe_unmanaged_teams: true`，`protected_teams` 名单内的除外，默认保护 `sidebar_*`）。
2. **强制按名单分队**：成员按 `members` 写入对应队伍；在线玩家立即生效，离线玩家由 `PlayerJoinEvent` 在下次登录时补入。
3. **记分板同步**：`scoreboard.enabled` 为真时创建/更新 objective（默认 `nt_teams`，侧边栏 `display_name`），每个队伍一行（`prefix + display_name`，按 `color` 上色），分数 = 人数。
4. **离线可用**：API 不可达时用 `plugins/NorthTeam/cache/config-<id>.json`（并提示使用的是缓存、版本时间）。
5. **重启自愈**：`auto_reapply_on_start: true` 时开服用 `state.json` 记录的配置 ID + 缓存自动重放（scoreboard team 不持久化）。
