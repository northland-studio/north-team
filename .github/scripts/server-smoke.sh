#!/usr/bin/env bash
# ==========================================================================
# NorthTeam 真机验收脚本（Paper 26.2 + 真实插件 jar）
#
# 做什么：
#   1. 下载 Paper 26.2-124 并校验 sha256（与生产服务器同一构建）
#   2. 接受 EULA、online-mode=false、开启 RCON
#   3. 把 gradle build 产出的插件 jar 放进 plugins/
#   4. 用「FIFO 重定向 stdin」的方式启动服务端，等待日志出现 "Done ("
#   5. 依次通过 stdin 发送 /nt 命令，并把输出断言到日志
#   6. 停服，产出报告
#
# 两种验收模式（自动选择）：
#   full     —— 提供了 NT_SERVER_KEY 且官网可达：走完整断言
#              （apply 真实生效、4 个队伍、侧边栏 4 行、规则字段往返一致）
#   degraded —— 没有密钥 / 官网不可达：只断言「插件能启用、命令已注册、
#              失败路径给出中文可读提示、绝不抛栈崩溃」
#
# 退出码：0 全部通过；1 有断言失败（并生成 ci-result.txt）
# ==========================================================================
set -uo pipefail

log()  { printf '[smoke] %s\n' "$*"; }
ok()   { printf '[smoke][ OK ] %s\n' "$*"; }
bad()  { printf '[smoke][FAIL] %s\n' "$*"; FAILED=1; }

FAILED=0

# ---------------------------------------------------------------- 参数 ----
PAPER_URL="${PAPER_URL:?必须设置 PAPER_URL}"
PAPER_SHA256="${PAPER_SHA256:?必须设置 PAPER_SHA256}"
PAPER_JAR_NAME="${PAPER_JAR_NAME:-paper-26.2-124.jar}"
PLUGIN_JAR="${PLUGIN_JAR:?必须设置 PLUGIN_JAR（gradle build 产出的插件 jar）}"
NT_TEST_CONFIG_ID="${NT_TEST_CONFIG_ID:-3}"
NT_API_BASE="${NT_API_BASE:-https://xuanjian.top}"
NT_SERVER_KEY="${NT_SERVER_KEY:-}"
WORKDIR="${WORKDIR:-.ci-server}"
RESULT_FILE="${RESULT_FILE:-ci-result.txt}"
CMD_WAIT="${CMD_WAIT:-4}"
JAVA_BIN="${JAVA_BIN:-java}"

if [ ! -f "$PLUGIN_JAR" ]; then
  bad "找不到插件 jar：$PLUGIN_JAR"
  exit 1
fi

# 关键：先把 jar 路径转成绝对路径。下面会 cd 进 $WORKDIR，届时相对路径（build/libs/…）
# 会失效，cp 静默失败（本脚本是 set -uo pipefail，没有 -e）→ 插件不会进 plugins/，
# 服务端启动后 "Initialized 0 plugins"，所有 /nt 命令报 Unknown command。
PLUGIN_JAR="$(cd "$(dirname "$PLUGIN_JAR")" && pwd)/$(basename "$PLUGIN_JAR")"
log "插件 jar（绝对路径）：$PLUGIN_JAR"

# ------------------------------------------------------- 准备服务端目录 ----
rm -rf "$WORKDIR"
mkdir -p "$WORKDIR/plugins"
cd "$WORKDIR" || exit 1

log "下载 Paper：$PAPER_URL"
if ! curl -fsSL -o "$PAPER_JAR_NAME" "$PAPER_URL"; then
  bad "Paper 下载失败"
  exit 1
fi

ACTUAL_SHA="$(sha256sum "$PAPER_JAR_NAME" | awk '{print $1}')"
if [ "$ACTUAL_SHA" != "$PAPER_SHA256" ]; then
  bad "Paper sha256 不匹配：期望 $PAPER_SHA256，实际 $ACTUAL_SHA"
  exit 1
fi
ok "Paper sha256 校验通过（$ACTUAL_SHA）"

PLUGIN_BASENAME="$(basename "$PLUGIN_JAR")"
if ! cp "$PLUGIN_JAR" plugins/; then
  bad "复制插件到 plugins/ 失败：$PLUGIN_JAR"
  exit 1
fi
if [ ! -f "plugins/$PLUGIN_BASENAME" ]; then
  bad "插件未出现在 plugins/ 目录（服务端会 Initialized 0 plugins）"
  exit 1
fi
ok "插件已放入 plugins/：$PLUGIN_BASENAME（$(stat -c%s "plugins/$PLUGIN_BASENAME") 字节）"
unzip -p "plugins/$PLUGIN_BASENAME" plugin.yml > /dev/null 2>&1 \
  && ok "插件 jar 内含 plugin.yml" \
  || bad "插件 jar 内没有 plugin.yml"

# EULA + 服务端配置（online-mode=false 是离线模式成员名生效的前提）
printf 'eula=true\n' > eula.txt
cat > server.properties <<'PROPS'
online-mode=false
enable-rcon=true
rcon.port=25575
rcon.password=ntcircon
motd=NorthTeam CI
spawn-protection=0
max-players=20
view-distance=4
simulation-distance=4
level-name=world
level-type=minecraft\:normal
generate-structures=false
PROPS

# --------------------------------------------------------- 探测官网 ----
# 样例配置 id=3 是**已公示**的，插件在未配置 server_key 时会自动走公开读通道
# （契约 3.4 GET /api/team/public、3.5 GET /api/team/public/:id，均无需认证），
# 因此即使 CI 没有配置 NT_SERVER_KEY secret，也能跑完整的 apply 验收。
MODE="degraded"
HTTP="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$NT_API_BASE/api/team/public" 2>/dev/null || echo 000)"
if [ "$HTTP" = "200" ]; then
  MODE="full"
fi
log "验收模式：$MODE（公开接口探测 HTTP=$HTTP；NT_SERVER_KEY=$([ -n "$NT_SERVER_KEY" ] && echo 已提供-插件通道 || echo 未提供-公开读通道)）"

# --------------------------------------------------------- 启动服务端 ----
log "启动 Paper 26.2（-Xmx1G --nogui）…"
# 用 FIFO 保持 stdin 常开：这样可以在服务端运行中持续写命令，
# 比「一次性把命令管道喂进去」可靠得多（后者会在命令发完时关闭 stdin）。
mkfifo server.stdin
exec 3<>server.stdin

# 环境变量 NORTHTEAM_SERVER_KEY 会被插件读取并覆盖 config.yml 的 server_key，
# 因此密钥不需要写进任何文件、也不会进 git。
NORTHTEAM_SERVER_KEY="$NT_SERVER_KEY" \
NORTHTEAM_API_BASE="$NT_API_BASE" \
  "$JAVA_BIN" -Xmx1G -jar "$PAPER_JAR_NAME" --nogui < server.stdin > server.log 2>&1 &
MC_PID=$!
log "服务端 PID=$MC_PID"

READY=0
for _ in $(seq 1 180); do
  if grep -q 'Done (' server.log 2>/dev/null; then
    READY=1
    break
  fi
  if ! kill -0 "$MC_PID" 2>/dev/null; then
    log "服务端进程已退出，提前结束等待"
    break
  fi
  sleep 2
done

# 把彩色/分节符剥离，得到便于 grep 的纯文本日志
plainify() {
  sed -e 's/\x1b\[[0-9;]*m//g' -e 's/§[0-9a-fk-orA-FK-ORx]//g' server.log > server-plain.log 2>/dev/null || cp server.log server-plain.log
}
plainify

if [ "$READY" != "1" ]; then
  bad "等待 'Done (' 超时（360s），服务端可能启动失败"
  echo "----- server.log 末尾 80 行 -----"
  tail -n 80 server.log
else
  ok "服务端已就绪（日志出现 'Done ('）"
fi

send_cmd() {
  log ">>> $1"
  printf '%s\n' "$1" >&3 2>/dev/null || log "（写入 stdin 失败，服务端可能已停止）"
  sleep "$CMD_WAIT"
  plainify
}

# --------------------------------------------------------- 发送命令 ----
if [ "$READY" = "1" ]; then
  send_cmd "nt list"
  send_cmd "nt status"
  send_cmd "nt info $NT_TEST_CONFIG_ID"
  send_cmd "nt apply $NT_TEST_CONFIG_ID --dry-run"
  send_cmd "nt status"
  send_cmd "nt apply $NT_TEST_CONFIG_ID"
  send_cmd "nt status"
  send_cmd "nt reload"
  send_cmd "stop"
fi

# 等待进程结束（最多 60s），拿不到的日志也要尽量保留
for _ in $(seq 1 30); do
  kill -0 "$MC_PID" 2>/dev/null || break
  sleep 2
done
if kill -0 "$MC_PID" 2>/dev/null; then
  log "服务端未自行退出，强制结束"
  kill -9 "$MC_PID" 2>/dev/null || true
fi
exec 3>&- 2>/dev/null || true
plainify

# --------------------------------------------------------- 断言 ----
LOG=server-plain.log
has()      { grep -qF -- "$1" "$LOG"; }
has_not()  { ! grep -qF -- "$1" "$LOG"; }

expect()      { if has "$1";      then ok "$2"; else bad "$2（日志中缺少：$1）"; fi; }
expect_absent() { if has_not "$1"; then ok "$2"; else bad "$2（日志中出现了：$1）"; fi; }

log "================= 基础断言 ================="
expect "NorthTeam v1.0.0" "插件 enable 日志出现"
expect "已启用" "插件启用提示出现"
expect_absent "Unknown command" "/nt 命令已注册（无 Unknown command）"
expect_absent "An internal error occurred" "没有服务端内部错误"
expect_absent "at top.xuanjian.northteam" "没有本插件异常栈"

log "================= 模式断言（$MODE）================="
if [ "$MODE" = "full" ]; then
  # dry-run 只打印计划
  expect "[预演]" "apply --dry-run 进入预演模式"
  expect "新建队伍：4 个" "预演统计出 4 个新建队伍"

  # 预演无副作用：预演那一行之后仍应出现「0 个队伍」的状态
  DRYRUN_LINE="$(grep -nF "[预演]" "$LOG" | head -1 | cut -d: -f1)"
  ZERO_AFTER="$(awk -v start="$DRYRUN_LINE" -v needle="服务端正式队伍（nt_*）：0 个" \
      'NR>start && index($0, needle)>0 {print NR; exit}' "$LOG")"
  if [ -n "$ZERO_AFTER" ]; then
    ok "apply --dry-run 未产生副作用（预演后队伍数仍为 0）"
  else
    bad "apply --dry-run 疑似产生副作用（预演后未见「0 个」状态）"
  fi

  # 真实 apply
  expect "已应用配置" "apply 成功执行"
  expect "来源：官网" "配置来自官网接口（不是缓存回退）"

  # 队伍与侧边栏真的建起来了
  expect "nt_yellow" "队伍 nt_yellow 已创建"
  expect "nt_red" "队伍 nt_red 已创建"
  expect "nt_blue" "队伍 nt_blue 已创建"
  expect "nt_green" "队伍 nt_green 已创建"
  expect "服务端正式队伍（nt_*）：4 个" "服务端存在 4 个受管正式队伍"
  expect "记分板 objective nt_teams" "侧边栏 objective nt_teams 已创建"
  expect "行数=4" "侧边栏渲染 4 行"

  # 契约字段 → Bukkit 选项的往返一致性（blue / green 的差异字段）
  expect "颜色=blue" "颜色映射正确（blue）"
  expect "友伤=true" "friendly_fire 映射正确"
  expect "名牌=hideForOtherTeams" "nametag_visibility 映射正确"
  expect "死亡消息=hideForOtherTeams" "death_message_visibility 映射正确"
  expect "碰撞=pushOtherTeams" "collision_rule=pushOtherTeams 映射正确"
  expect "碰撞=never" "collision_rule=never 映射正确"

  # reload 可用
  expect "config.yml 已重新加载" "/nt reload 生效"
else
  # 降级模式（官网不可达）：验证失败路径是「中文可读提示」而不是崩溃
  log "官网不可达，降级为验证「优雅失败」路径"
  expect "当前服务端" "status 仍能输出状态"
  expect_absent "NullPointerException" "没有空指针异常"
  expect_absent "Exception in thread" "没有未捕获异常"
fi

# --------------------------------------------------------- 汇总 ----
{
  echo "mode=$MODE"
  echo "http_probe=$HTTP"
  echo "ready=$READY"
  echo "config_id=$NT_TEST_CONFIG_ID"
  if [ "$FAILED" = "0" ]; then
    echo "result=PASS"
  else
    echo "result=FAIL"
  fi
} > smoke-summary.txt

if [ "$FAILED" != "0" ]; then
  {
    echo "NorthTeam CI 真机验收失败"
    echo "时间：$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    echo "模式：$MODE（官网探测 HTTP=$HTTP）"
    echo "配置 ID：$NT_TEST_CONFIG_ID"
    echo
    echo "===== smoke-summary.txt ====="
    cat smoke-summary.txt
    echo
    echo "===== server.log 末尾 120 行 ====="
    tail -n 120 server.log 2>/dev/null || echo "(无 server.log)"
  } > "../$RESULT_FILE"
  bad "验收未通过，详情见 $RESULT_FILE"
  exit 1
fi

log "全部断言通过（模式=$MODE）"
exit 0
