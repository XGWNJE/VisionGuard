#!/usr/bin/env bash
set -euo pipefail

# VisionGuard Server — 一键同步部署脚本
# 用法: bash server/deploy.sh [--full] [--nginx]
#   默认:  仅同步 src/ 并重建重启
#   --full: 同时同步 package.json 并 npm install
#   --nginx: 同时更新 Nginx 配置并 reload

VPS_HOST="root@216.36.111.208"
# 域名上线后可改为: VPS_HOST="root@xgwnje.cn"
VPS_PORT="${VPS_PORT:-53111}"
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
echo "远程: $VPS_HOST:$VPS_PATH"
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
ssh -p "$VPS_PORT" "$VPS_HOST" "rm -rf ${VPS_PATH}/src_new && mkdir -p ${VPS_PATH}/src_new"
scp -P "$VPS_PORT" -r "$SCRIPT_DIR/src/" "$VPS_HOST:${VPS_PATH}/src_new/"
ssh -p "$VPS_PORT" "$VPS_HOST" "cd ${VPS_PATH} && rm -rf src && mv src_new/src src && rm -rf src_new"
echo "  OK"
echo ""

# 3. 同步 package.json (--full)
if $FULL; then
    echo "[3/6] 同步 package.json + npm install ..."
    scp -P "$VPS_PORT" "$SCRIPT_DIR/package.json" "$VPS_HOST:${VPS_PATH}/package.json"
    ssh -p "$VPS_PORT" "$VPS_HOST" "cd ${VPS_PATH} && npm install"
    echo "  OK"
    echo ""
else
    echo "[3/6] 跳过依赖同步 (--full 启用)"
    echo ""
fi

# 4. Nginx 配置 (--nginx)
if $NGINX; then
    echo "[4/6] 同步 Nginx 配置..."
    scp -P "$VPS_PORT" "$SCRIPT_DIR/nginx-visionguard.conf" "$VPS_HOST:/etc/nginx/sites-available/xgwnje.cn"
    ssh -p "$VPS_PORT" "$VPS_HOST" "ln -sf /etc/nginx/sites-available/xgwnje.cn /etc/nginx/sites-enabled/ && nginx -t && systemctl reload nginx"
    echo "  OK"
    echo ""
else
    echo "[4/6] 跳过 Nginx (--nginx 启用)"
    echo ""
fi

# 5. 远程编译
echo "[5/6] 远程编译..."
ssh -p "$VPS_PORT" "$VPS_HOST" "cd ${VPS_PATH} && npm run build"
echo "  OK"
echo ""

# 6. 重启服务
echo "[6/6] 重启 visionguard 服务..."
ssh -p "$VPS_PORT" "$VPS_HOST" "systemctl restart visionguard && sleep 2 && systemctl status visionguard --no-pager -l | head -15"
echo ""

REMOTE_VER=$(ssh -p "$VPS_PORT" "$VPS_HOST" "node -e \"console.log(require('${VPS_PATH}/package.json').version)\"" 2>/dev/null || echo "unknown")
echo "=== 部署完成 — 服务器版本: ${REMOTE_VER} ==="
