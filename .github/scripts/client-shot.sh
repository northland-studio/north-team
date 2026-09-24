#!/usr/bin/env bash
# ==========================================================================
# NorthTeam 客户端截图验收（**实验性 / 非阻塞**）
#
# 目标：在 CI 里用 xvfb + mesa 起两个离线模式的无头客户端连进本地 Paper，
#       截图 tab 列表 / 聊天 / 侧边栏，作为「玩家视角」的证据。
#
# !!! 现状与卡点（如实记录，务必先读）!!!
#   本脚本**未经过实际 CI 运行验证**。原因：
#     · 26.2 客户端的 version manifest、依赖库与 asset index 在 CI 上的可用性
#       需要多轮迭代才能确认；
#     · 客户端还需要 OpenGL（mesa 软渲染）、资源文件与启动参数配合；
#     · 客户端的 --quickPlayMultiplayer 在离线模式下的行为也需要实测。
#   因此本脚本被单独放在 client-screenshots.yml 中，并且：
#     · 只在 workflow_dispatch 手动触发（或显式开启）时运行；
#     · 永远不阻塞主验收（job 上 continue-on-error: true，脚本始终 exit 0）；
#     · 无论成功失败都会写出 client-result.txt 说明「走到哪一步、卡在哪」。
#   权威验收以 server-test.yml 的服务端断言为准（它会 dump 游戏内真实
#   scoreboard 状态：队伍数、颜色、前缀、规则、侧边栏行数）。
#
# 用法（本地或 CI）：
#   MC_VERSION=26.2 SERVER_HOST=127.0.0.1 SERVER_PORT=25565 bash client-shot.sh
# ==========================================================================
set -uo pipefail

MC_VERSION="${MC_VERSION:-26.2}"
SERVER_HOST="${SERVER_HOST:-127.0.0.1}"
SERVER_PORT="${SERVER_PORT:-25565}"
CLIENTS="${CLIENTS:-2}"
SHOT_DIR="${SHOT_DIR:-client-shots}"
WORK="${WORK:-.ci-client}"
RESULT="${RESULT:-client-result.txt}"
WAIT_BOOT="${WAIT_BOOT:-90}"

mkdir -p "$SHOT_DIR" "$WORK"
STEP="init"
note() { printf '[client] %s\n' "$*"; }

finish() {
  {
    echo "NorthTeam 客户端截图验收（实验性）"
    echo "时间：$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    echo "Minecraft 版本：$MC_VERSION"
    echo "最后完成的步骤：$STEP"
    echo "结果：$1"
    echo
    echo "说明：该路径为实验性，未阻塞主验收；权威验收见 server-test.yml 的服务端断言。"
  } > "$RESULT"
  echo "----- $RESULT -----"
  cat "$RESULT"
  # 始终成功退出：本 job 是「尽力而为」，不应让主流水线变红
  exit 0
}

command -v python3 >/dev/null || { STEP="check-python"; finish "跳过：缺少 python3"; }
command -v java    >/dev/null || { STEP="check-java";   finish "跳过：缺少 java"; }

# --------------------------------------------------- 1. 解析版本清单 ----
STEP="fetch-version-manifest"
note "获取 Mojang 版本清单…"
MANIFEST="$WORK/version_manifest_v2.json"
if ! curl -fsSL --max-time 60 -o "$MANIFEST" \
    "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"; then
  finish "失败：无法下载版本清单（CI 可能无法访问 Mojang）"
fi

STEP="resolve-version-json"
VERSION_URL="$(python3 - "$MANIFEST" "$MC_VERSION" <<'PY'
import json, sys
manifest, target = sys.argv[1], sys.argv[2]
data = json.load(open(manifest, encoding="utf-8"))
for v in data.get("versions", []):
    if v.get("id") == target:
        print(v["url"]); break
PY
)"
if [ -z "$VERSION_URL" ]; then
  finish "失败：版本清单里找不到 Minecraft $MC_VERSION"
fi
note "版本 JSON：$VERSION_URL"

STEP="download-version-json"
VERSION_JSON="$WORK/version.json"
curl -fsSL --max-time 60 -o "$VERSION_JSON" "$VERSION_URL" \
  || finish "失败：无法下载版本 JSON"

# ------------------------------- 2. 下载 client.jar 与依赖库 ----
STEP="download-client-and-libs"
note "下载 client.jar 与依赖库（这一步通常最慢/最易失败）…"
if ! python3 - "$VERSION_JSON" "$WORK" <<'PY'
import json, os, sys, urllib.request, hashlib

version_json, work = sys.argv[1], sys.argv[2]
data = json.load(open(version_json, encoding="utf-8"))
libs_dir = os.path.join(work, "libs")
os.makedirs(libs_dir, exist_ok=True)

def download(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    with urllib.request.urlopen(url, timeout=180) as r, open(dest, "wb") as f:
        f.write(r.read())

# 客户端主 jar
client = data["downloads"]["client"]
download(client["url"], os.path.join(work, "client.jar"))

# 只处理 linux 上适用的依赖库（带 rules 的按 os 规则过滤）
rules = data.get("libraries", [])
entries = []
for lib in rules:
    allowed = True
    for rule in lib.get("rules", []):
        action = rule.get("action")
        os_name = (rule.get("os") or {}).get("name")
        if os_name and os_name != "linux":
            allowed = action != "allow"
        elif os_name == "linux":
            allowed = action == "allow"
    if not allowed:
        continue
    art = lib.get("downloads", {}).get("artifact")
    if not art:
        continue  # 只取 artifact，跳过 natives/classifier（CI 渲染用软渲染即可）
    path = os.path.join(libs_dir, art["path"].replace("/", os.sep))
    download(art["url"], path)
    entries.append(path)

with open(os.path.join(work, "classpath.txt"), "w", encoding="utf-8") as f:
    f.write(":".join(entries))
print("libraries:", len(entries))
PY
then
  finish "失败：client.jar 或依赖库下载失败"
fi
note "依赖库下载完成"

# ------------------------------------------- 3. asset index（可选）----
STEP="download-asset-index"
ASSET_INDEX_ID="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8')).get('assetIndex',{}).get('id',''))" "$VERSION_JSON" 2>/dev/null)"
ASSETS_DIR="$WORK/assets"
mkdir -p "$ASSETS_DIR/indexes"
if [ -n "$ASSET_INDEX_ID" ]; then
  python3 - "$VERSION_JSON" "$ASSETS_DIR" <<'PY' 2>/dev/null || true
import json, os, sys, urllib.request
version_json, assets = sys.argv[1], sys.argv[2]
data = json.load(open(version_json, encoding="utf-8"))
idx = data.get("assetIndex", {})
if idx.get("url"):
    dest = os.path.join(assets, "indexes", idx["id"] + ".json")
    if not os.path.exists(dest):
        with urllib.request.urlopen(idx["url"], timeout=180) as r, open(dest, "wb") as f:
            f.write(r.read())
PY
  note "asset index=$ASSET_INDEX_ID（不下载完整资源，仅保证客户端能启动；画面可能缺贴图）"
else
  note "未找到 assetIndex，继续尝试"
fi

# --------------------------------------------------- 4. 起客户端 ----
STEP="launch-clients"
CP="$(cat "$WORK/classpath.txt")"
[ -f "$WORK/client.jar" ] || finish "失败：client.jar 不存在"

command -v xvfb-run >/dev/null || finish "跳过/失败：缺少 xvfb-run（请在 workflow 里安装 xvfb）"

LAUNCH_LOG="$WORK/client.log"
: > "$LAUNCH_LOG"
for i in $(seq 1 "$CLIENTS"); do
  USER_NAME="NT_CI_Client$i"
  note "启动客户端 $i：$USER_NAME"
  # 离线模式：accessToken 随便填，--quickPlayMultiplayer 直接进服
  xvfb-run -a --server-args="-screen 0 1280x720x24" \
    java -Xmx1G \
      -Djava.library.path="$WORK/libs" \
      -cp "$WORK/client.jar:$CP" \
      net.minecraft.client.main.Main \
      --username "$USER_NAME" \
      --version "$MC_VERSION" \
      --gameDir "$WORK/game-$i" \
      --assetsDir "$ASSETS_DIR" \
      --assetIndex "$ASSET_INDEX_ID" \
      --accessToken 0 \
      --userType legacy \
      --quickPlayMultiplayer "$SERVER_HOST:$SERVER_PORT" \
      >> "$LAUNCH_LOG" 2>&1 &
  sleep 3
done
note "客户端已拉起，等待 $WAIT_BOOT 秒让它们连入并渲染…"
sleep "$WAIT_BOOT"

# --------------------------------------------------- 5. 截图 ----
STEP="screenshot"
if command -v import >/dev/null; then
  for i in $(seq 1 "$CLIENTS"); do
    DISP=":$(( 99 + i ))"
    import -display "$DISP" -window root "$SHOT_DIR/client-$i.png" 2>/dev/null \
      && note "已截图 client-$i.png" \
      || note "客户端 $i 截图失败（display $DISP 不可用或客户端未渲染）"
  done
else
  note "缺少 imagemagick 的 import 命令，跳过截图"
fi

# 收尾
pkill -f "net.minecraft.client.main.Main" 2>/dev/null || true

STEP="done"
if ls "$SHOT_DIR"/*.png >/dev/null 2>&1; then
  finish "成功：已产出截图（见 artifact）"
else
  finish "失败：未能产出截图（客户端未能在 CI 中渲染；详见 client.log）"
fi
