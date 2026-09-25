# NorthTeam

**队伍信息采集公示系统的游戏侧组件** —— Paper 服务端插件。

管理员在官网 `https://xuanjian.top` 的后台编排队伍（队名、颜色、前缀、友伤/隐身/名牌/碰撞规则、成员名单），
在游戏里执行一条 `/nt apply <配置ID>`，插件就把这套配置**全量重建**到服务端的记分板队伍上，
并同步侧边栏计分板（每队一行、分数为该队人数）。

官网侧是独立仓库，两边唯一的接口约定是 [`docs/API.md`](docs/API.md)（契约 v1）。

| | |
|---|---|
| 目标服务端 | Paper 26.2（构建 124） |
| 编译目标 | Java 25 |
| 插件 API 风格 | Bukkit 风格 `plugin.yml`（不使用 paper-plugin.yml） |
| JSON | Gson（Shadow relocate 到 `top.xuanjian.northteam.libs.gson`） |
| 文本 | MiniMessage 为主，兼容 `&` 传统颜色码 |
| 构建位置 | **GitHub Actions（本仓库不做本地构建）** |

---

## 1. 快速开始（服务器管理员）

1. 从 GitHub Actions 下载插件 jar（见第 3 节），放到服务器的 `plugins/` 目录。
2. 启动一次服务器，插件会生成 `plugins/NorthTeam/config.yml`。
3. 编辑 `config.yml`，至少填好 `api.server_key`（见第 5 节）。
4. 执行 `/nt reload`，然后 `/nt list` 看看能不能拉到官网配置。
5. `/nt apply <配置ID>` 应用。

---

## 2. 命令与权限

| 命令 | 权限 | 说明 |
|---|---|---|
| `/nt list` | `northteam.view` | 拉取官网配置列表，列出 ID / 名称 / 日期 / 队伍数 / 人数 / 是否公示 / 是否当前已应用 |
| `/nt info <id>` | `northteam.view` | 展示配置详情（每队颜色、前缀、后缀、规则、人数），并**标出与当前服务端状态的差异** |
| `/nt status` | `northteam.view` | 已应用配置、缓存文件、上次同步时间、API 可达性，以及**当前服务端真实记分板状态** |
| `/nt apply <id> [--dry-run] [--force]` | `northteam.admin` | 全量重建队伍与侧边栏 |
| `/nt import [名称]` | `northteam.admin` | 采集服务器全部 scoreboard team，回传官网新建一条配置 |
| `/nt reload` | `northteam.admin` | 重读 `config.yml`（地址、密钥、开关） |

权限节点（均声明在 `plugin.yml`，默认都是 OP）：

```yaml
northteam.admin:  # apply / import / reload
  default: op
northteam.view:   # list / info / status
  default: op
```

`northteam.admin` 隐含 `northteam.view`：只给管理员 `northteam.admin` 即可查看+操作；
也可以用权限插件只给某人 `northteam.view` 让他只读。

Tab 补全支持：子命令、`--dry-run` / `--force` 选项，以及从官网拉到的配置 ID
（ID 列表异步预热缓存，补全时不会发网络请求）。

### `/nt apply` 的语义（全量重建）

1. **删除配置外的受管队伍** —— 只在本插件命名空间（`nt_` / `sb_`）内删除，
   `apply.protected_teams` 名单内的永不删除（支持 `*` 通配）。
   把 `apply.wipe_unmanaged_teams` 设为 `false` 则只建不删。
2. **按 `key` 建/更新队伍** —— 正式队伍名 = `nt_` + `key`。
3. **清空并重写成员** —— 在线玩家立即入队；离线玩家记录在案，
   由 `PlayerJoinEvent` 在他们下次登录时补入。
4. **同步记分板** —— 见第 4 节。
5. **写 `state.json`** —— 记录配置 ID 与 version，供下次 apply 做去重。

`version` 与 `state.json` 中相同则跳过应用（提示需要 `--force`）；
`--dry-run` 只打印将要发生的变更（新建/更新/删除队伍数、重分队玩家数、待登录补入人数、侧边栏行数），
**不修改任何服务端状态**。

---

## 3. CI 构建与验收

> **本仓库不做本地构建。** 不需要在本机装 Gradle / JDK，
> 所有编译、打包、真机验收都在 GitHub Actions 上完成。

### 3.1 三个流水线

| 流水线 | 触发 | 职责 |
|---|---|---|
| [`build.yml`](.github/workflows/build.yml) | push / PR / 手动 | JDK 25 + Gradle 9.5.1，`gradle build`，上传插件 jar |
| [`server-test.yml`](.github/workflows/server-test.yml) | push/PR 到 `main` / 手动 | **真机验收**：起真实 Paper 26.2，跑 `/nt` 命令并断言 |
| [`client-screenshots.yml`](.github/workflows/client-screenshots.yml) | 仅手动 | 实验性无头客户端截图，失败不阻塞 |

### 3.2 怎么拿到插件 jar

1. 打开仓库的 **Actions** 页面，点进最近一次成功的 `build` 运行。
2. 在页面底部 **Artifacts** 里下载 `NorthTeam-jar`。
3. 解压得到 `NorthTeam-<version>.jar`（例如 `NorthTeam-1.0.1.jar`），
   这就是可安装的 fat jar（Gson 已 relocate，不会与其它插件冲突）。

也可以从 `server-test` 运行的 artifact `server-test-artifacts` 里拿到
服务端日志、`state.json`、缓存文件，便于排查。

### 3.3 本地复现构建（可选，非必需）

如果你确实想在本机构建，需要 JDK 25 + Gradle 9.2 以上（本仓库不含 gradle wrapper）：

```bash
gradle build --no-daemon
# 产物：build/libs/NorthTeam-1.0.1.jar
```

### 3.4 真机验收怎么做的

`server-test.yml` 会：

1. `gradle build` 出插件 jar；
2. 下载 `paper-26.2-124.jar` 并**校验 sha256**
   （`274bbcb9807ad79a479617ce695a7c0686e652a8d1cabfd0e288d92a3ea29593`，与生产同一构建）；
3. 写 `eula=true`、`online-mode=false`、开 RCON，把插件放进 `plugins/`；
4. 后台起服务器，等日志出现 `Done (` 后，**通过 stdin**（FIFO 保持常开）依次执行：
   `nt list` → `nt status` → `nt info 3` → `nt apply 3 --dry-run` → `nt status`
   → `nt apply 3` → `nt status` → `nt reload`；
5. 断言（日志已剥离颜色码后做字面匹配）：
   - 插件 enable 日志出现、`/nt` 命令已注册（无 `Unknown command`）；
   - `--dry-run` 打印「新建队伍：4 个」，且**之后**队伍数仍为 0（证明预演无副作用）；
   - 真实 apply 后：`nt_yellow` / `nt_red` / `nt_blue` / `nt_green` 四个队伍存在、
     受管正式队伍数为 4、objective `nt_teams` 存在且**行数=4**；
   - 契约字段 → Bukkit 选项的往返一致：`颜色=blue`、`友伤=true`、
     `名牌=hideForOtherTeams`、`死亡消息=hideForOtherTeams`、
     `碰撞=pushOtherTeams`（blue）与 `碰撞=never`（green）；
   - 全程没有 `An internal error occurred`、没有本插件异常栈；
6. 上传完整服务端日志 + 摘要为 artifact；失败时把诊断写进 `ci-result.txt` 并提交回分支。

测试数据来自官网**公开接口**（无需认证）：
`GET https://xuanjian.top/api/team/public` 与 `/api/team/public/3`。
样例配置 **ID = 3**（「CI 测试用例 · 周年庆大逃杀」，4 队 11 人，`is_public=1`）
写在 `server-test.yml` 的 env `NT_TEST_CONFIG_ID` 里；样例由官网维护，
**ID 变更时只需要改这个变量**。

#### 需要在仓库里配置的 Secret

完整验收需要一个有插件通道权限的密钥：

> 仓库 **Settings → Secrets and variables → Actions → New repository secret**
> 名称：`NT_SERVER_KEY`，值：官网后台 `mod_servers.server_key`

密钥通过环境变量 `NORTHTEAM_SERVER_KEY` 注入插件，**不会写进任何文件、不会进 git**。
没有配置该 secret（或 runner 访问不到官网）时，流水线自动降级为
**优雅降级验收**：仍然验证插件能启用、命令已注册、失败路径给出中文可读提示、
不抛栈崩溃 —— 即「API 还没部署好时不会报错崩溃」这一条也能被自动验证。

#### 客户端截图的卡点（如实说明）

`client-screenshots.yml` 想在 CI 里用 xvfb + mesa 起两个离线模式无头客户端连进来，
截图 tab 列表 / 聊天 / 侧边栏。**这条路径目前没有跑通验证过**，已知卡点：

- 26.2 客户端的 version manifest / 依赖库 / assetIndex 在 runner 上的可用性；
- 客户端需要 OpenGL 上下文（mesa 软渲染）与正确的 natives、启动参数，
  `--quickPlayMultiplayer` 在离线模式下的行为也需要实测，通常要多轮迭代；
- 完整 assets 体积很大，脚本只下 assetIndex，画面可能缺贴图。

因此该流水线**只支持手动触发**、job 上 `continue-on-error: true`、脚本始终 `exit 0`，
无论成败都产出 `client-result.txt` 写明卡在哪一步，**绝不阻塞主验收**。

权威验收以 `server-test.yml` 的服务端断言为准：它的 `/nt status` 会 dump 出
**游戏内真实 scoreboard 状态**（受管队伍数、每队颜色/前缀/规则/成员数、
objective 名与行数），足以证明记分板同步真的生效，而不是只打印了几句日志。

---

## 4. 记分板同步

`scoreboard.enabled` 为真时（且本地 `scoreboard.master_enabled` 也为真）：

- 创建/更新 objective（默认名 `nt_teams`，标题取 `scoreboard.display_name`，MiniMessage）；
- **侧边栏**采用经典做法：**每队一行，每行一个记分板队伍**。
  第 i 行的队伍名为 `sb_<i>`，把行文本（`prefix + display_name`，按该队 `color` 上色）
  放进该队伍的 prefix，再用一个不可见的 entry 挂分数，分数 = 该队人数。
- 行队伍统一用 `sb_` 前缀，与 `/team` 使用的正式队伍（`nt_<key>`）**分开命名**，
  避免互相干扰；`sb_*` 也在默认 `protected_teams` 里。

关于「人数」的口径：分数取**配置名单里的人数**（契约 `score_mode=member_count`），
不是当前在线的玩家数。离线成员在登录后才会真正入队，但记分板上的数字始终等于名单人数，
这样公示口径稳定。

`position` 的另外两个取值 `list` / `below_name` 在原版里是「按玩家显示」，
无法使用行队伍方案 —— 此时插件改为给每个成员 entry 上分（分数为其所属队伍人数），
并清理掉侧边栏行队伍。

### 所有权与安全

插件只在自己命名空间内创建/删除队伍，并且**清理前会校验指纹**
（侧边栏队伍必须含有本插件生成的不可见 entry）。
即便别的插件也用了 `sb_` 前缀，也不会被误删。

### 聊天栏队伍前缀（1.0.1 新增）

**问题**：Bukkit/Paper 的默认聊天渲染用的是 `player.getName()`，不带记分板队伍装饰，
所以**队伍前缀不会出现在聊天栏**；而进退服消息走的是 `displayName`，前缀能显示出来 ——
两边不一致（对应老问题 [SPIGOT-564](https://hub.spigotmc.org/jira/browse/SPIGOT-564)）。

**做法**：插件监听 Paper 的 `AsyncChatEvent`，按 `chat.format` 模板自己渲染聊天行，
前缀取值仍然是官网后台配置里的 `prefix` / `suffix` / `color`（单一数据源，不用去
LuckPerms 再维护一份）。队伍归属按**已应用配置的成员名单**匹配（大小写不敏感），
所以在异步线程里也不碰记分板。

```yaml
chat:
  enabled: true
  format: "{prefix}{player}<gray>: </gray>{message}"
  format_no_team: "{player}<gray>: </gray>{message}"
```

占位符：`{prefix}` `{suffix}` `{player}` `{displayname}` `{team}` `{team_key}` `{message}`
（`{player}` 会自动用所属队伍的 `color` 上色）。字面量部分支持 MiniMessage 与 `&` 颜色码；
未知占位符按字面量保留，只会在 `/nt reload` 时告警，不会让整条聊天消失。

注意事项：

- 如果有别的插件（例如 EssentialsXChat）也在渲染聊天，两个 renderer 会互相覆盖 ——
  这时把 `chat.enabled` 设为 `false`，只留一个插件负责聊天渲染；
- 玩家必须**在队伍成员名单里**才有前缀：改完名单要 `/nt apply <id>`（在线即时生效），
  离线玩家靠 `member.assign_on_join` 在下次登录补入。

---

## 5. 配置项说明（`config.yml`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `api.base` | `https://xuanjian.top` | 官网站点根。契约路径是 `/api/team/...`，**不要带 `/api`**；误填了会自动剥掉多余后缀 |
| `api.server_key` | `""` | `X-Server-Key` 密钥，取自官网 `mod_servers.server_key`。留空则接口全部 401 并给出提示 |
| `api.connect_timeout_seconds` | `5` | 连接超时 |
| `api.request_timeout_seconds` | `15` | 请求超时 |
| `auto_reapply_on_start` | `true` | 开服按 `state.json` 的配置 ID + 缓存自动重放（记分板不持久化，重启必丢，这项是重启自愈的关键） |
| `apply.wipe_unmanaged_teams` | `true` | 是否删除配置外的受管队伍（全量重建） |
| `apply.protected_teams` | `["sidebar_*", "sb_*"]` | 永不删除的队伍，支持 `*` 通配 |
| `scoreboard.master_enabled` | `true` | 本地记分板总开关，`false` 时同步官网要求也不做 |
| `scoreboard.max_rows` | `15` | 侧边栏最多渲染多少行 |
| `member.assign_on_join` | `true` | 离线玩家登录时自动补入其队伍 |
| `chat.enabled` | `true` | 是否由本插件渲染聊天行（Paper 默认渲染不带队伍前缀，故默认开启） |
| `chat.format` | `"{prefix}{player}<gray>: </gray>{message}"` | 有队伍成员的聊天模板；留空则不改动（回到默认渲染） |
| `chat.format_no_team` | `"{player}<gray>: </gray>{message}"` | 不在任何队伍里的玩家使用的模板；留空则不改动 |
| `debug` | `false` | 输出 HTTP 请求等调试日志 |

### 密钥从哪来

1. 登录官网后台，找到 **服务器密钥**（`mod_servers.server_key`）——与 `/api/mod/*` 是同一套密钥。
2. 填进 `plugins/NorthTeam/config.yml` 的 `api.server_key`，执行 `/nt reload`；
3. 或者设置环境变量 `NORTHTEAM_SERVER_KEY`（优先级更高，推荐在容器/CI 里用，避免密钥落盘）。

对接示例：

```yaml
api:
  base: "https://xuanjian.top"
  server_key: "在这里粘贴官网 mod_servers.server_key"
auto_reapply_on_start: true
apply:
  wipe_unmanaged_teams: true
  protected_teams: ["sidebar_*", "sb_*"]
scoreboard:
  master_enabled: true
  max_rows: 15
chat:
  enabled: true
  format: "{prefix}{player}<gray>: </gray>{message}"
  format_no_team: "{player}<gray>: </gray>{message}"
```

---

## 6. 离线可用与重启自愈

- **离线可用**：官网不可达时，`/nt apply <id>` 会回退到
  `plugins/NorthTeam/cache/config-<id>.json`，并明确提示
  「使用缓存（版本时间 X，缓存时间 Y）」。每次成功从官网拉取都会刷新该缓存。
- **重启自愈**：`auto_reapply_on_start: true` 时，开服 3 秒后（异步拉配置、主线程 apply）
  用 `state.json` 里记录的配置 ID 重放一次。**重启后必然无条件重放**（不受 version 去重影响），
  因为 scoreboard team 不持久化。
- **绝不抛栈给玩家**：所有 API 失败、JSON 解析失败、字段非法都会翻译成中文可读提示；
  `/nt apply` 遇到非法配置会先列出全部问题再中止，不修改服务端任何状态。

---

## 7. 项目结构

```
src/main/java/top/xuanjian/northteam/
  NorthTeamPlugin.java        插件入口：装配、启动自愈、异步/主线程调度、消息
  command/NorthTeamCommand.java  /nt 全部子命令 + Tab 补全
  api/TeamApiClient.java       契约 3.1/3.2/3.3 的 HTTP 客户端（X-Server-Key）
  api/ApiException.java        已翻译成中文的失败原因（区分不可达/HTTP/格式/配置）
  model/                       契约 2.1 的数据模型（record）+ 命名空间常量
  apply/TeamApplier.java       全量重建语义
  apply/ScoreboardSyncer.java  记分板/侧边栏同步（每队一行）
  apply/ScoreboardInspector.java 读取服务端真实状态 + 与配置做差异对比
  apply/ServerCollector.java   /nt import 的采集逻辑
  apply/ApplyPlan.java         变更预演
  config/PluginConfig.java     config.yml 强类型视图 + 环境变量覆盖
  state/PluginState.java       state.json 与 cache/config-<id>.json
  listener/PlayerJoinListener.java  离线玩家登录补入
  util/                        MiniMessage 解析、契约枚举、校验器、所有权指纹
src/main/resources/plugin.yml, config.yml
.github/workflows/            build / server-test / client-screenshots
.github/scripts/              server-smoke.sh（真机验收）、client-shot.sh（实验性）
```

---

## 8. 已知限制

1. **记分板队伍名长度**：契约允许 `key` 最长 16 字符，插件按契约拼成 `nt_<key>`，
   最长 19 字符；而原版记分板队伍名历史上限是 16 字符。
   插件会**保留契约行为**并在校验时给出警告。建议官网把 `key` 限制在 13 字符内。
2. **侧边栏排序**：原版侧边栏按分数从高到低排列，而契约规定分数 = 队伍人数，
   因此行的显示顺序由人数决定，`sort_order` 只用于 apply 时的处理顺序。
3. **`scoreboard.position` 为 `list` / `below_name`** 时无法使用行队伍方案，
   改为给每个成员 entry 上分（详见第 4 节）。
4. 切换 position 后，旧位置残留的分值不会自动全部清理（只清理本插件记录的成员）。
5. `/nt import` 采集的是**服务器上全部 scoreboard team**（跳过本插件内部的侧边栏行队伍）。
   如果服务器上有其它插件创建了大量队伍，它们也会被一起采集，请在官网侧按需删减。

---

## 9. 相关文档

- [`docs/API.md`](docs/API.md) —— 官网 ↔ 插件接口契约 v1（**唯一约定，改它必须同步改两边实现**）
- [`docs/IMPLEMENTATION-NOTES.md`](docs/IMPLEMENTATION-NOTES.md) —— 实现笔记：
  关键技术决策、API 版本核对结论、契约疑问与建议
