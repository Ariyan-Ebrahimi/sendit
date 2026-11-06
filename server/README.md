# Sendit relay server

Handles Sendit accounts (sign up / sign in) and worldwide transfers: a phone uploads a file, gets a 6-character code, and anyone with the code can download it for 24 hours. Expired files are deleted automatically every 30 minutes.

Built with Node.js and PostgreSQL. It runs on port **4000**. The app gets its server address at build time from `sendit.serverUrl` in `sendit/local.properties`.

---

## Deploy to a VPS

The VPS needs Ubuntu or Debian, and you need its root password.

### Option A: one command from this PC
In PowerShell, from this `server` folder:

```powershell
powershell -ExecutionPolicy Bypass -File deploy\deploy.ps1 -HostIp YOUR_SERVER_IP
```

This packs the server, copies it to `/opt/sendit` on the VPS and runs `deploy/setup-vps.sh` there. You'll type the root password twice. At the end it checks `http://YOUR_SERVER_IP:4000/api/health` from your PC.

### Option B: by hand
```bash
# on your PC
scp -r server root@YOUR_SERVER_IP:/opt/sendit
# on the VPS
ssh root@YOUR_SERVER_IP
bash /opt/sendit/deploy/setup-vps.sh
```

### What setup-vps.sh does
1. Installs PostgreSQL and Node.js 20 (only if Node 18+ isn't installed already).
2. **First run only:** creates the `sendit_db` database and `sendit_user` role with a random password, and writes `.env` with a random `JWT_SECRET`. On later runs it keeps the existing `.env`, so accounts and sessions stay valid.
3. Installs npm packages, then creates and starts a `sendit` systemd service that restarts on crash and starts on boot.
4. Opens port 4000/tcp in ufw or firewalld, if one is active.
5. Runs a health check.

If the app still can't connect, open **TCP 4000** in your VPS provider's firewall panel too.

### Updating the server later
Run `deploy\deploy.ps1 -HostIp YOUR_SERVER_IP` again. It replaces the code and restarts the service, and keeps `.env`, the database and uploaded files.

### Handy commands on the VPS
```bash
systemctl status sendit          # is it running?
journalctl -u sendit -f          # live logs
systemctl restart sendit         # restart
curl http://127.0.0.1:4000/api/health
```

### Coexisting with Rostam
Rostam on the same VPS uses ports 8787, 7880, 7881 and 443. Sendit only uses 4000, so they don't clash.

---

## Settings (`.env`)

| Key | Default | Meaning |
|---|---|---|
| `PORT` | 4000 | Port to listen on |
| `HOST` | 0.0.0.0 | Address to bind to |
| `DATABASE_URL` | (required) | PostgreSQL connection string |
| `JWT_SECRET` | (required) | Long random string. The server refuses to start without one |
| `MAX_FILE_SIZE_MB` | 500 | Largest upload allowed |
| `TRANSFER_TTL_HOURS` | 24 | How long a code and its file are kept |

## API

- `GET  /api/health`: server and database status
- `POST /api/auth/signup`: `{ name, email, password }` (password at least 6 characters) → `{ token, user }`
- `POST /api/auth/login`: `{ email, password }` → `{ token, user }`
- `GET  /api/auth/me`: needs `Authorization: Bearer <token>`
- `POST /api/transfers/upload`: multipart with a `file` field and an optional `senderName` → `{ code, fileName, fileSize, downloadUrl, expiresAt }`
- `GET  /api/transfers/:code`: file details
- `GET  /api/transfers/download/:code`: the file itself

Anywhere shares (what the app uses):

- `POST /api/shares`: `{ senderName, files:[{name,size,mime}], ttlHours: 1|24|168, oneTime, password?, toEmail? }` → `{ code, ownerKey, url, expiresAt, recipientName }`
- `GET  /api/shares/:code/files/:id/offset`: how many bytes already arrived (to resume an upload)
- `PUT  /api/shares/:code/files/:id`: raw bytes, with headers `X-Owner-Key` and `X-Upload-Offset`
- `POST /api/shares/:code/complete`, `GET /api/shares/:code/status`, `DELETE /api/shares/:code`
- `GET  /api/my/shares`, `DELETE /api/my/shares/:code`: the signed-in user's live shares
- `GET  /api/inbox`: shares sent to the signed-in user
- `POST /api/users/lookup`: `{ email }` → `{ name }`
- `GET  /s/:code`: a download page for browsers. `/s/:code/manifest.json` gives the file list and `/s/:code/f/:id` gives a file. Downloads can resume with Range requests. Password-protected shares need the `X-Share-Password` header or `?p=` in the URL.

Sign-in and sign-up are limited to 30 attempts per 15 minutes per IP. Code lookups are limited to 60 per minute.

## Later: HTTPS with a domain
Traffic is plain HTTP for now. When you have a domain, point it at the VPS and put Caddy in front (`reverse_proxy 127.0.0.1:4000`). Then change the app's server to `https://your-domain`. To change it in the app, tap "Server" at the bottom of the sign-in screen, or use Settings → Advanced → Server address.
