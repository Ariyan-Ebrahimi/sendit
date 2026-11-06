# Sendit

Send files from your phone to anyone, anywhere: pick files, get a 6-character code, and the other person types it in (or opens the link) to download.

- `BeamDrop/` — Android app (Kotlin, Jetpack Compose). English, Persian and Turkish.
- `server/` — relay server (Node.js, Express, PostgreSQL) with an admin panel at `/admin`. See [server/README.md](server/README.md).

## Features

- Send by code, link or QR, or straight to contacts and groups (end-to-end encrypted)
- Nearby Wi-Fi transfer without the internet
- Password, one-time download, send later, location-locked and self-destructing files
- Vault with its own PIN, file gallery, transfer history and stats
- Request files from others through a web upload page
- Plans and voucher codes, support chat, reports, admin dashboard

## Build the app

1. Put your server address in `BeamDrop/local.properties` (this file is not committed):
   ```properties
   sendit.serverUrl=http://your-server:4000
   ```
   Without it the app uses `http://10.0.2.2:4000` (your PC from the Android emulator).
2. Build:
   ```bash
   cd BeamDrop
   ./gradlew assembleRelease
   ```
   The APK is in `BeamDrop/app/build/outputs/apk/release/`.

## Run the server

See [server/README.md](server/README.md). Copy `server/.env.example` to `server/.env` and fill it in, or use `server/deploy/setup-vps.sh` on a fresh Ubuntu/Debian VPS.
