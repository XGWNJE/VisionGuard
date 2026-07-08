#!/usr/bin/env bash
set -euo pipefail

# Dedicated VisionGuard server-code deploy.
# Client release packages are handled by scripts/publish-release.ps1.
#
# Usage:
#   bash server/deploy.sh [--install] [--dry-run]
#
# Reads VPS_IP / SSH_USER / SSH_PORT / SSH_PASSWORD from:
#   SERVER_INFRA_ENV=../Server-infra/server.local.env

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_INFRA_ENV="${SERVER_INFRA_ENV:-$REPO_ROOT/../Server-infra/server.local.env}"
REMOTE_ROOT="${VISIONGUARD_REMOTE_ROOT:-/opt/visionguard-server}"
SERVICE_NAME="${VISIONGUARD_SERVICE_NAME:-visionguard}"
INSTALL=false
DRY_RUN=false

for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=true ;;
    --dry-run) DRY_RUN=true ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

load_env() {
  local env_file="$1"
  if [[ ! -f "$env_file" ]]; then
    echo "Missing Server-infra env file: $env_file" >&2
    exit 1
  fi

  while IFS='=' read -r key value; do
    [[ -z "${key:-}" || "$key" =~ ^[[:space:]]*# ]] && continue
    key="$(echo "$key" | tr -d '[:space:]')"
    value="${value%$'\r'}"
    value="${value#\"}"
    value="${value%\"}"
    value="${value#\'}"
    value="${value%\'}"
    case "$key" in
      VPS_IP|SSH_USER|SSH_PORT|SSH_PASSWORD|SSH_KEY|SSH_KEY_PATH)
        export "$key=$value"
        ;;
    esac
  done < "$env_file"
}

load_env "$SERVER_INFRA_ENV"

: "${VPS_IP:?VPS_IP missing in $SERVER_INFRA_ENV}"
: "${SSH_USER:?SSH_USER missing in $SERVER_INFRA_ENV}"
SSH_PORT="${SSH_PORT:-22}"

ARCHIVE="$(mktemp -t visionguard-server-XXXXXX.tgz)"
REMOTE_ARCHIVE="/tmp/visionguard-server-deploy.tgz"
REMOTE_STAGE="/tmp/visionguard-server-deploy"

cleanup() {
  rm -f "$ARCHIVE"
}
trap cleanup EXIT

echo "== VisionGuard server deploy =="
echo "server: $SCRIPT_DIR"
echo "env: $SERVER_INFRA_ENV"
echo "remote: $SSH_USER@$VPS_IP:$SSH_PORT:$REMOTE_ROOT"
echo "install deps: $INSTALL"

if [[ "$DRY_RUN" == true ]]; then
  echo "Dry run complete."
  exit 0
fi

echo ""
echo "== Local build =="
npm --prefix "$SCRIPT_DIR" run build

echo ""
echo "== Create deploy archive =="
tar -C "$SCRIPT_DIR" -czf "$ARCHIVE" package.json package-lock.json dist

REMOTE_COMMAND=$(cat <<EOF
set -euo pipefail
rm -rf "$REMOTE_STAGE"
mkdir -p "$REMOTE_STAGE" "$REMOTE_ROOT"
tar -xzf "$REMOTE_ARCHIVE" -C "$REMOTE_STAGE"
rm -rf "$REMOTE_ROOT/dist"
mv "$REMOTE_STAGE/dist" "$REMOTE_ROOT/dist"
cp "$REMOTE_STAGE/package.json" "$REMOTE_ROOT/package.json"
cp "$REMOTE_STAGE/package-lock.json" "$REMOTE_ROOT/package-lock.json"
if $INSTALL; then
  cd "$REMOTE_ROOT" && npm ci --omit=dev
fi
systemctl restart "$SERVICE_NAME"
sleep 2
systemctl is-active --quiet "$SERVICE_NAME"
curl -fsS http://127.0.0.1:3000/health >/dev/null
rm -rf "$REMOTE_STAGE" "$REMOTE_ARCHIVE"
EOF
)

if [[ -n "${SSH_PASSWORD:-}" ]]; then
  echo ""
  echo "== Upload and deploy via Paramiko =="
  export VG_DEPLOY_ARCHIVE="$ARCHIVE"
  export VG_DEPLOY_REMOTE_ARCHIVE="$REMOTE_ARCHIVE"
  export VG_DEPLOY_REMOTE_COMMAND="$REMOTE_COMMAND"
  python - <<'PY'
import os
import sys

try:
    import paramiko
except Exception as exc:
    raise SystemExit("Paramiko is required for password-based deploy: " + str(exc))

host = os.environ["VPS_IP"]
user = os.environ["SSH_USER"]
port = int(os.environ.get("SSH_PORT", "22"))
password = os.environ.get("SSH_PASSWORD") or None
key_filename = os.environ.get("SSH_KEY") or os.environ.get("SSH_KEY_PATH") or None
archive = os.environ["VG_DEPLOY_ARCHIVE"]
remote_archive = os.environ["VG_DEPLOY_REMOTE_ARCHIVE"]
remote_command = os.environ["VG_DEPLOY_REMOTE_COMMAND"]

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(hostname=host, username=user, port=port, password=password, key_filename=key_filename, timeout=30)
try:
    sftp = client.open_sftp()
    sftp.put(archive, remote_archive)
    stdin, stdout, stderr = client.exec_command(remote_command)
    status = stdout.channel.recv_exit_status()
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    if out:
        print(out, end="")
    if status != 0:
        if err:
            print(err, file=sys.stderr, end="")
        raise SystemExit(status)
finally:
    client.close()
PY
else
  echo ""
  echo "== Upload and deploy via OpenSSH =="
  SSH_TARGET="$SSH_USER@$VPS_IP"
  scp -P "$SSH_PORT" "$ARCHIVE" "$SSH_TARGET:$REMOTE_ARCHIVE"
  ssh -p "$SSH_PORT" "$SSH_TARGET" "$REMOTE_COMMAND"
fi

echo ""
echo "== Server deploy complete =="
