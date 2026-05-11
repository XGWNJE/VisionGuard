#!/bin/bash
# VisionGuard TLS 证书申请脚本
# 域名: xgwnje.cn
# 使用: bash setup-tls.sh

set -e

DOMAIN="xgwnje.cn"
EMAIL="admin@xgwnje.cn"   # 修改为你的邮箱

echo "=== VisionGuard TLS Setup ==="
echo "域名: $DOMAIN"
echo ""

# 1. 安装 Nginx (如未安装)
if ! command -v nginx &> /dev/null; then
    echo "[1/5] 安装 Nginx..."
    apt update
    apt install -y nginx
    systemctl enable nginx
else
    echo "[1/5] Nginx 已安装"
fi

# 2. 创建 certbot 验证目录
echo "[2/5] 创建验证目录..."
mkdir -p /var/www/certbot

# 3. 部署 Nginx 配置 (先 HTTP only，certbot 需要验证)
echo "[3/5] 部署初始 Nginx 配置..."
cp nginx-visionguard.conf /etc/nginx/sites-available/xgwnje.cn
ln -sf /etc/nginx/sites-available/xgwnje.cn /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
echo "   Nginx 配置已部署 (HTTP 模式)"

# 4. 安装 certbot 并申请证书
echo "[4/5] 申请 Let's Encrypt 证书..."
if ! command -v certbot &> /dev/null; then
    apt install -y certbot python3-certbot-nginx
fi

certbot --nginx \
    -d "$DOMAIN" \
    -d "www.$DOMAIN" \
    --non-interactive \
    --agree-tos \
    --email "$EMAIL" \
    --redirect

echo "   证书申请完成"

# 5. 配置自动续期
echo "[5/5] 配置自动续期..."
grep -q "certbot renew" /etc/crontab 2>/dev/null || \
    echo "0 3 * * * root certbot renew --quiet --post-hook 'systemctl reload nginx'" >> /etc/crontab

echo ""
echo "=== TLS 配置完成 ==="
echo "测试: curl -I https://$DOMAIN"
echo ""
echo "注意: 确保 DNS A 记录已指向本机 IP ($(curl -s ifconfig.me 2>/dev/null || echo '未知'))"
