# NorthTeam 实现笔记

本文件是**插件侧的实现记录**，不是接口契约。
官网 ↔ 插件的唯一约定仍然是 [`API.md`](API.md)，本文件不修改、不覆盖契约。

---

## 1. 版本与工具链（已核对，非猜测）

### 1.1 Paper API 版本号

Paper 从 26.2 起**不再发布 `*-R0.1-SNAPSHOT`**。核对
`https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml`
后确认 26.2 系列形如 `26.2.build.124-stable`（该系列到 `26.2.build.129-stable`）。

- 本仓库钉 **`io.papermc.paper:paper-api:26.2.build.124-stable`**，
  与生产服务端 `paper-26.2-124` 完全对齐。
- 已确认 `paper-api-26.2.build.124-stable.pom` 与 `.jar` 均返回 HTTP 200。

### 1.2 plugin.yml 的 api-version

- Paper 26.2 接受范围是 `1.13` – `26.2`；`api-version` 可选，省略时按 legacy 插件加载并打警告。
- 本插件写 `api-version: '26.2'`。**必须加引号**，否则 YAML 会把 `26.2` 解析成浮点数。

### 1.3 构建

- Gradle 9.5.1 + `com.gradleup.shadow` **9.6.1**（官方兼容表：Shadow 9.5.0+ 要求 Gradle ≥ 9.2）。
- 仓库**不含** gradle wrapper 二进制；CI 用 `gradle/actions/setup-gradle` 提供 `gradle` 命令。
- `jar` 任务被禁用，只保留 `shadowJar`（classifier 为空），
  因此 `build/libs/` 下只有一个 `NorthTeam-<version>.jar`，不会出现两个 jar 造成混淆。

### 1.4 Paper 26.2 API 关键核对结论

以下均在 `jd.papermc.io/paper/26.2/`（标题为 "paper-api 26.2.build.129-stable API"）
与 Adventure 5.2.0 javadoc 上逐条核对过：

| 项目 | 结论 |
|---|---|
| `Team.displayName/prefix/suffix(Component)`、`color(NamedTextColor)` | 存在（Adventure 版）；旧 String 版已 `@Deprecated` 但仍在 |
| `Team.setAllowFriendlyFire` / `setCanSeeFriendlyInvisibles` | 存在 |
| `Team.Option` | 三个常量都在：`NAME_TAG_VISIBILITY`、`DEATH_MESSAGE_VISIBILITY`、`COLLISION_RULE` |
| `Team.OptionStatus` | `ALWAYS`、`NEVER`、`FOR_OTHER_TEAMS`、`FOR_OWN_TEAM` |
| `Criteria` | **不再是 enum**，是 `interface Criteria`，`DUMMY` 是 `static final` 字段 |
| `Scoreboard.registerNewObjective` | 非废弃重载是 `(String, Criteria, @Nullable Component)` |
| `Objective.displayName(Component)` / `setDisplaySlot(DisplaySlot)` | 存在（**没有** Adventure 风格的 `displaySlot(...)`） |
| `Scoreboard.clearSlot()` | **无参版本不存在**，只有 `clearSlot(DisplaySlot)` |
| `Score` | `setScore(int)` / `getScore()` / `resetScore()` / `isScoreSet()` 都在 |
| `NamedTextColor` | 16 个常量，是 `GRAY`/`DARK_GRAY`（**没有** `GREY`） |
| `Bukkit.getScheduler()` | 仍在、未废弃；`runTask` / `runTaskAsynchronously` / `runTaskLaterAsynchronously` 都在 |
| `PlayerJoinEvent` / `Listener` / `@EventHandler` / `EventPriority` | 未变 |

> 注：核对用的是 `26.2.build.129-stable` 的 javadoc，而我们依赖 `26.2.build.124-stable`。
> 同一 minor 内的这两个 build 在上述 API 上没有差异；CI 的真实编译会给出最终确认。

---

## 2. 为什么这样实现

### 2.1 对 API 变更「免疫」的两处设计

1. **队伍选项用 `Enum.valueOf` 按名取常量，而不是直接引用常量。**
   原版历史上确实移除过某些队伍选项；`TeamProperties.optionOrNull("DEATH_MESSAGE_VISIBILITY")`
   这种写法即使将来某个常量被删掉，也只是「取不到 → 跳过并提示」，不会编译失败或运行时抛异常。
2. **颜色用 16 个具名 `NamedTextColor` 常量 + 自有反查表。**
   反向查询（import 方向）走本类自己的 `Map`，不依赖 Adventure `NameMap` 的方法签名。

### 2.2 侧边栏「每队一行」

原版侧边栏只能显示「entry + 分数」，不能直接显示一行任意文本。
经典做法是**为每一行创建一个 scoreboard team**，把行文本放进该队伍的 prefix，
再用一个不可见的 entry（`§` + 十六进制字符，渲染宽度为 0）挂分数。

- 行队伍命名 `sb_1`、`sb_2`…，与 `/team` 使用的正式队伍 `nt_<key>` 分开。
- 行文本 = `prefix + display_name`，用 `colorIfAbsent(该队 color)` 上色
  （prefix 里已有显式颜色的部分不会被覆盖）。
- 分数 = 该队名单人数。

### 2.3 所有权指纹（避免误删别人的队伍）

`--wipe` / 侧边栏回收都必须能区分「本插件创建的队伍」与「别人的队伍」。
本插件的侧边栏队伍一定含有形如 `§a§0§r` 的不可见 entry，
`SidebarEntries.isOurs()` 校验这个格式；只有「state.json 记录过」**或**
「名字是 `sb_` 且带本插件指纹」的队伍才会被回收。
即使别的插件也用 `sb_` 前缀也不会被误删。

### 2.4 线程模型

Bukkit 记分板不是线程安全的，而 HTTP 是阻塞的，因此严格分离：

- **网络**（`/nt list`、export、import、启动自愈拉配置）→ `runTaskAsynchronously`；
- **记分板读写**（apply、采集、inspector dump）→ `runTask`（主线程）。

`/nt status` 因此是「主线程采集状态 → 异步探测 API 可达性 → 回主线程输出」三段式。

### 2.5 绝不把栈抛给玩家

- `ApiException` 只承载已经翻译成中文的原因，并区分
  `UNREACHABLE` / `HTTP_ERROR` / `MALFORMED` / `CONFIG` 四类，命令层据此给不同措辞。
- `MiniMessages.parse` 三级降级：MiniMessage → 传统 `&` 码解析 → 纯文本，永不抛异常。
- `TeamApplier.apply` 整体包在 `try/catch(RuntimeException)` 里，异常只写日志 + 提示一行中文。
- 配置校验先收集**全部**问题再中止，且中止时不修改服务端任何状态。

### 2.6 启动自愈为什么强制重放

`/nt apply` 用 version 去重是给「管理员反复敲同一条命令」用的；
但**重启后记分板是空的**（team 不持久化），此时 version 相同也必须重建，
因此 `scheduleAutoReapply` 走的是 `force = true`。

---

## 3. 与契约的疑问与建议

> 以下都是**按契约实现**后的反馈，不是擅自改动。契约文件本身未被修改。

### 3.1 `key` 长度与记分板队伍名上限（建议修改契约）

契约 2.1 规定 `key` 为 `[a-z0-9_]{1,16}`，同时规定「插件用它做 scoreboard team 名（前缀 `nt_`）」。
两者叠加后队伍名最长 **3 + 16 = 19 字符**，超过原版记分板队伍名 16 字符的历史上限。

本实现的选择：**按契约原样拼 `nt_<key>`**，并在校验时给出警告、在 README「已知限制」里写明。
建议二选一：

- 把 `key` 上限收紧到 **13**（`nt_` + 13 = 16），或
- 契约里明确「插件可对超长 key 做截断/哈希」，由插件保证唯一性。

### 3.2 侧边栏顺序与 `sort_order`

契约规定侧边栏**分数 = 该队人数**，而原版侧边栏按分数从高到低排序。
结果是：**行的显示顺序由人数决定**，`sort_order` 无法决定视觉顺序。
如果官网期望「按 sort_order 从上到下显示」，那么分数就不能等于人数
（常规做法是给每行分配递减的占位分数，把真实人数放到别处显示）。
本实现按契约：分数 = 人数，`sort_order` 只用于处理顺序。

### 3.3 `scoreboard.position` 为 `list` / `below_name` 时语义不明

「每队一行」的方案只适用于 `sidebar`。`list`（tab 列表）与 `below_name`（名牌下方）
在原版里都是**按玩家显示分数**，不存在「一行一支队伍」的概念。
契约没有说明这两种位置下「分数 = 该队人数」具体渲染成什么。

本实现的解释：改为给每个成员 entry 上分为其所属队伍的人数，并清理侧边栏行队伍。
**建议契约补充说明**，或本期先把 `position` 限定为 `sidebar`。

### 3.4 `death_message_visibility` 的可实现性

核对确认 26.2 上 `Team.Option.DEATH_MESSAGE_VISIBILITY` 仍存在，因此该字段可以真正生效。
但原版历史上曾移除过这一队伍选项，一旦将来再次移除，插件只能跳过该字段并提示。
建议契约保留该字段的同时，注明「服务端不支持时插件跳过」。

### 3.5 `version` 去重的语义边界

契约说「相同即跳过应用」。在**重启后**这一条如果照字面执行，会导致队伍永远建不回来
（记分板为空，但 version 未变 → 跳过 → 没有队伍）。
本实现只在**显式的 `/nt apply`** 上做 version 去重，启动自愈强制重放。
建议契约在 4.5「重启自愈」里明确这一点。

### 3.6 `POST /api/team/import` 请求体的字段集合

契约 2.1 含 `is_public`，而 3.3 的示例请求体**不含** `is_public`（默认由官网置 0）。
本实现按示例：回传体**不带** `is_public`，也不带 `id` / `version`，
并且显式带上 `"event_date": null`，以与示例逐字一致。

### 3.7 `/nt status` 的输出范围

契约 4 只要求 status 显示「已应用配置、缓存文件、上次同步时间、API 可达性」。
本实现额外 dump 了**当前服务端真实 scoreboard 状态**（受管队伍数、每队颜色/前缀/规则/成员数、
objective 名与行数）。这样做有两个理由：

1. 契约要求 `/nt info` 标出「与当前服务器状态的差异」，本来就需要读取真实状态；
2. CI 真机验收需要一个能证明「记分板真的同步了」的断言依据（见 README 3.4 节）。

如果官网/契约希望 status 保持精简，可以把这一段收敛为一行摘要。

---

## 4. CI 与验收

- `build.yml`：JDK 25 + Gradle 9.5.1 + `gradle build`，上传 `NorthTeam-jar`。
- `server-test.yml`：下载并 sha256 校验 `paper-26.2-124.jar` → 起服 → 等 `Done (` →
  通过 stdin 依次执行 `nt list` / `nt status` / `nt info 3` / `nt apply 3 --dry-run` /
  `nt status` / `nt apply 3` / `nt status` / `nt reload` → 断言 →
  上传日志 artifact；失败时写 `ci-result.txt` 并提交回分支。
- 验收脚本：`.github/scripts/server-smoke.sh`。
  提供 `NT_SERVER_KEY` secret 且官网可达时跑**完整断言**，否则自动降级为
  **优雅降级断言**（插件能启用、命令已注册、失败提示可读、不抛栈）。
- `client-screenshots.yml`：实验性无头客户端截图，**仅手动触发、失败不阻塞**，
  卡点已在 workflow 注释与 README 中写明。
