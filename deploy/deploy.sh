#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

release=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
root=${SPLITEASY_ROOT:-/opt/spliteasy}
# Compose gives shell exports precedence over env files; the server and release files are authoritative.
unset BACKEND_IMAGE FRONTEND_IMAGE POSTGRES_PASSWORD APP_JWT_SECRET SITE_ADDRESS PUBLIC_ORIGIN HTTP_PORT HTTPS_PORT
[[ "$root" = /* && -f "$root/.env" && -f "$release/release.env" ]] || {
  echo 'Missing server .env or release.env, or SPLITEASY_ROOT is not absolute.' >&2; exit 1;
}
# Only immutable paired application images are accepted by the release path.
[[ $(grep -c '^' "$release/release.env") -eq 2 ]] || {
  echo 'release.env must contain exactly the two image assignments.' >&2; exit 1;
}
for image in BACKEND_IMAGE FRONTEND_IMAGE; do
  grep -Eq "^${image}=ghcr.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$" "$release/release.env" || {
    echo "Invalid digest-addressed $image in release.env" >&2; exit 1;
  }
done
exec 9>"$root/deploy.lock"
flock -w 600 9
export SPLITEASY_LOCK_HELD=1
dc() {
  docker compose -p spliteasy --env-file "$root/.env" --env-file "$release/release.env" \
    -f "$release/compose.prod.yml" "$@"
}
trap 'echo "Release failed; current still records the last successful release. Check containers and schema before rolling back." >&2' ERR
dc config --quiet
dc pull backend frontend
dc up -d --no-build --wait --wait-timeout 90 postgres
SPLITEASY_ROOT="$root" SPLITEASY_RELEASE="$release" bash "$release/deploy/backup.sh"
dc up -d --no-build --wait --wait-timeout 180 backend frontend
dc exec -T backend curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness > /dev/null
origin=$(dc exec -T backend printenv APP_CORS_ALLOWED_ORIGINS)
[[ "$origin" =~ ^https?://[a-zA-Z0-9.:-]+$ ]] || { echo 'Invalid PUBLIC_ORIGIN' >&2; exit 1; }
curl --fail --silent --show-error --connect-timeout 5 --max-time 20 --retry 3 --retry-delay 2 \
  "$origin/api/health" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
curl --fail --silent --show-error --connect-timeout 5 --max-time 20 --retry 3 --retry-delay 2 \
  "$origin/login" | grep -q '<app-root'

# Atomic pointer replacement: do not mark a failed release active or restore the DB automatically.
python3 - "$root" "$release" <<'PY'
import os, sys
from pathlib import Path
root, release = map(Path, sys.argv[1:])
current = root / 'current'
if current.is_symlink() and current.resolve() != release:
    old = root / '.previous.next'
    old.unlink(missing_ok=True)
    old.symlink_to(current.resolve())
    os.replace(old, root / 'previous')
next_link = root / '.current.next'
next_link.unlink(missing_ok=True)
next_link.symlink_to(release)
os.replace(next_link, current)
PY
echo "Activated release $(basename "$release")"
