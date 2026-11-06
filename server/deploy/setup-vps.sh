#!/usr/bin/env bash
# Sendit relay server: one-shot setup for an Ubuntu/Debian VPS. Run as root from /opt/sendit:
#   bash deploy/setup-vps.sh
# Safe to run again after copying new server files (keeps the existing .env and database).
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE=sendit
APP_USER=sendit
DB_NAME=sendit_db
DB_USER=sendit_user
PORT="${PORT:-4000}"

say() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }

if [ "$(id -u)" -ne 0 ]; then echo "Please run as root (or with sudo)."; exit 1; fi
export DEBIAN_FRONTEND=noninteractive

say "Installing system packages"
apt-get update -y
apt-get install -y curl ca-certificates gnupg openssl postgresql

NODE_MAJOR=0
if command -v node >/dev/null 2>&1; then NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"; fi
if [ "$NODE_MAJOR" -lt 18 ]; then
  say "Installing Node.js 20"
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
echo "Node $(node -v), npm $(npm -v)"

say "Starting PostgreSQL"
systemctl enable --now postgresql

if [ ! -f "$APP_DIR/.env" ]; then
  say "Creating database and .env (first install)"
  DB_PASS="$(openssl rand -hex 18)"
  JWT_SECRET="$(openssl rand -hex 32)"
  if runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1; then
    runuser -u postgres -- psql -c "ALTER ROLE $DB_USER WITH LOGIN PASSWORD '$DB_PASS';"
  else
    runuser -u postgres -- psql -c "CREATE ROLE $DB_USER WITH LOGIN PASSWORD '$DB_PASS';"
  fi
  if ! runuser -u postgres -- psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
    runuser -u postgres -- psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"
  fi
  cat > "$APP_DIR/.env" <<ENV
PORT=$PORT
HOST=0.0.0.0
DATABASE_URL=postgres://$DB_USER:$DB_PASS@127.0.0.1:5432/$DB_NAME
JWT_SECRET=$JWT_SECRET
MAX_FILE_SIZE_MB=1024
TRANSFER_TTL_HOURS=24
ENV
  chmod 600 "$APP_DIR/.env"
else
  say "Keeping existing .env"
fi

# Settings added in newer versions (kept if already there).
grep -q '^ADMIN_PASSWORD=.' "$APP_DIR/.env" || { sed -i '/^ADMIN_PASSWORD=/d' "$APP_DIR/.env"; echo "ADMIN_PASSWORD=$(openssl rand -hex 9)" >> "$APP_DIR/.env"; }
grep -q '^USER_QUOTA_MB=' "$APP_DIR/.env" || echo "USER_QUOTA_MB=2048" >> "$APP_DIR/.env"

say "Creating service user and installing npm packages"
id -u "$APP_USER" >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
mkdir -p "$APP_DIR/uploads"
cd "$APP_DIR"
npm install --omit=dev --no-audit --no-fund
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

say "Installing systemd service ($SERVICE)"
cat > /etc/systemd/system/$SERVICE.service <<UNIT
[Unit]
Description=Sendit relay server
After=network-online.target postgresql.service
Wants=network-online.target postgresql.service

[Service]
Type=simple
User=$APP_USER
WorkingDirectory=$APP_DIR
ExecStart=$(command -v node) $APP_DIR/server.js
Restart=always
RestartSec=3
Environment=NODE_ENV=production
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=$APP_DIR/uploads

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable "$SERVICE"
systemctl restart "$SERVICE"

say "Opening firewall port $PORT/tcp (if a firewall is active)"
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow "$PORT/tcp"
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port="$PORT/tcp" && firewall-cmd --reload
else
  echo "No active ufw/firewalld found; nothing to do. (Also check your VPS provider's panel firewall.)"
fi

say "Health check"
for i in $(seq 1 15); do
  if curl -fsS "http://127.0.0.1:$PORT/api/health"; then echo; break; fi
  sleep 1
  if [ "$i" -eq 15 ]; then
    echo "Server did not answer. Recent logs:"; journalctl -u "$SERVICE" -n 40 --no-pager; exit 1
  fi
done

PUBLIC_IP="$(curl -fsS --max-time 5 https://api.ipify.org || hostname -I | awk '{print $1}')"
say "Done. Sendit relay is running at http://$PUBLIC_IP:$PORT"
echo "Admin:    http://$PUBLIC_IP:$PORT/admin   user: admin   password: $(grep '^ADMIN_PASSWORD=' "$APP_DIR/.env" | cut -d= -f2)"
echo "Logs:     journalctl -u $SERVICE -f"
echo "Restart:  systemctl restart $SERVICE"
