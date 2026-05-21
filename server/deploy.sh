#!/usr/bin/env bash
set -euo pipefail

# VisionGuard Server — 一键同步部署脚本
# 用法: bash server/deploy.sh [--full] [--nginx]
#   默认:  仅同步 src/ 并重建重启
#   --full: 同时同步 package.json 并 npm install
#   --nginx: 同时更新 Nginx 配置并 reload

# SSH 连接别名，定义于 ~/.ssh/config（IP/端口/密钥不硬编码在源码中）
# Host visionguard
#   HostName <实际IP>
#   Port <实际端口>
#   User root
#   IdentityFile ~/.ssh/id_ed25519
VPS_ALIAS="xgwnje"
VPS_PATH="/opt/visionguard/VisionGuard_Server"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

FULL=false
NGINX=false
for arg in "$@"; do
    [[ "$arg" == "--full" ]] && FULL=true
    [[ "$arg" == "--nginx" ]] && NGINX=true
done

echo "=== VisionGuard Server 部署 ==="
echo "本地: $SCRIPT_DIR"
echo "远程: $VPS_ALIAS:$VPS_PATH"
$FULL && echo "模式: 含依赖"
$NGINX && echo "模式: 含 Nginx"
echo ""

# 1. 类型检查
echo "[1/6] 本地类型检查..."
cd "$SCRIPT_DIR" && npx tsc --noEmit
echo "  OK"
echo ""

# 2. 同步 src/
echo "[2/6] 同步 src/ ..."
ssh "$VPS_ALIAS" "rm -rf ${VPS_PATH}/src_new && mkdir -p ${VPS_PATH}/src_new"
scp -r "$SCRIPT_DIR/src/" "$VPS_ALIAS:${VPS_PATH}/src_new/"
ssh "$VPS_ALIAS" "cd ${VPS_PATH} && rm -rf src && mv src_new/src src && rm -rf src_new"
echo "  OK"
echo ""

# 3. 同步 package.json (--full)
if $FULL; then
    echo "[3/6] 同步 package.json + npm install ..."
    scp "$SCRIPT_DIR/package.json" "$VPS_ALIAS:${VPS_PATH}/package.json"
    ssh "$VPS_ALIAS" "cd ${VPS_PATH} && npm install"
    echo "  OK"
    echo ""
else
    echo "[3/6] 跳过依赖同步 (--full 启用)"
    echo ""
fi

# 4. Nginx 配置 (--nginx)
if $NGINX; then
    echo "[4/6] 同步 Nginx 配置..."
    scp "$SCRIPT_DIR/nginx-visionguard.conf" "$VPS_ALIAS:/etc/nginx/sites-available/visionguard.xgwnje.cn"
    ssh "$VPS_ALIAS" "ln -sf /etc/nginx/sites-available/visionguard.xgwnje.cn /etc/nginx/sites-enabled/visionguard.xgwnje.cn && nginx -t && systemctl reload nginx"
    echo "  OK"
    echo ""
else
    echo "[4/6] 跳过 Nginx (--nginx 启用)"
    echo ""
fi

# 5. 远程编译
echo "[5/6] 远程编译..."
ssh "$VPS_ALIAS" "cd ${VPS_PATH} && npm run build"
echo "  OK"
echo ""

# 6. 重启服务
echo "[6/6] 重启 visionguard 服务..."
ssh "$VPS_ALIAS" "systemctl restart visionguard && sleep 2 && systemctl status visionguard --no-pager -l | head -15"
echo ""

REMOTE_VER=$(ssh "$VPS_ALIAS" "node -e \"console.log(require('${VPS_PATH}/package.json').version)\"" 2>/dev/null || echo "unknown")
echo "=== 部署完成 — 服务器版本: ${REMOTE_VER} ==="
