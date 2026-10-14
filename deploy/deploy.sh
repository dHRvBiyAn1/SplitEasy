#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

release=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
root=${SPLITEASY_ROOT:-/opt/spliteasy}
# Compose gives shell exports precedence over env files; the server and release files are authoritative.
unset BACKEND_IMAGE FRONTEND_IMAGE POSTGRES_PASSWORD APP_JWT_SECRET SITE_ADDRESS PUBLIC_ORIGIN API_ORIGIN TUNNEL_TOKEN_FILE HTTP_PORT HTTPS_PORT
[[ "$root" = /* && -f "$root/.env" && -f "$release/release.env" ]] || {
  echo 'Missing server .env or release.env, or SPLITEASY_ROOT is not absolute.' >&2; exit 1;
}
# Only the immutable backend image is accepted by the release path.
[[ $(grep -c '^' "$release/release.env") -eq 1 ]] || {
  echo 'release.env must contain exactly one backend image assignment.' >&2; exit 1;
}
grep -Eq '^BACKEND_IMAGE=ghcr.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$' "$release/release.env" || {
  echo 'Invalid digest-addressed BACKEND_IMAGE in release.env' >&2; exit 1;
}
exec 9>"$root/deploy.lock"
flock -w 600 9
export SPLITEASY_LOCK_HELD=1
dc() {
  docker compose -p spliteasy --env-file "$root/.env" --env-file "$release/release.env" \
    -f "$release/compose.prod.yml" "$@"
}
trap 'echo "Release failed; current still records the last successful release. Check containers and schema before rolling back." >&2' ERR
dc config --quiet
config=$(dc config --format json)
origin=$(printf '%s' "$config" | python3 -c 'import json,sys; print(json.load(sys.stdin)["x-api-origin"])')
[[ "$origin" == https://api.split-easy.io ]] || { echo 'Invalid API_ORIGIN' >&2; exit 1; }
token_file=$(printf '%s' "$config" | python3 -c 'import json,sys; print(next(v["source"] for v in json.load(sys.stdin)["services"]["cloudflared"]["volumes"] if v["target"] == "/run/secrets/tunnel-token"))')
python3 - "$token_file" <<'PYTOKEN'
import os, stat, sys
p = sys.argv[1]
if not os.path.isfile(p) or os.stat(p).st_size == 0 or stat.S_IMODE(os.stat(p).st_mode) & 0o077:
    sys.exit('Tunnel token must be a nonempty private file (mode 0400); value not printed.')
PYTOKEN
dc pull backend caddy cloudflared
# Verify the connector's UID can actually read the file without printing it.
dc run --rm --no-deps --user 65532:65532 --entrypoint /bin/sh \
  -v "$token_file:/run/secrets/tunnel-token:ro" caddy \
  -c 'test -s /run/secrets/tunnel-token && test -r /run/secrets/tunnel-token'

dc up -d --no-build --wait --wait-timeout 90 postgres
SPLITEASY_ROOT="$root" SPLITEASY_RELEASE="$release" bash "$release/deploy/backup.sh"
dc up -d --no-build --wait --wait-timeout 180 backend caddy cloudflared
dc exec -T backend curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness > /dev/null
curl --fail --silent --show-error --connect-timeout 5 --max-time 20 --retry 5 --retry-all-errors --retry-delay 3 \
  "$origin/api/health" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'

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
