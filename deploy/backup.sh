#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
root=${SPLITEASY_ROOT:-/opt/spliteasy}
release=${SPLITEASY_RELEASE:-$root/current}
unset BACKEND_IMAGE FRONTEND_IMAGE POSTGRES_PASSWORD APP_JWT_SECRET SITE_ADDRESS PUBLIC_ORIGIN HTTP_PORT HTTPS_PORT
[[ "$root" = /* && -f "$root/.env" && -f "$release/release.env" ]] || {
  echo 'Missing server or release configuration.' >&2; exit 1;
}
if [[ ${SPLITEASY_LOCK_HELD:-0} != 1 ]]; then
  exec 9>"$root/deploy.lock"
  flock -w 600 9
fi
mkdir -p "$root/backups"
tmp=$(mktemp "$root/backups/.backup.XXXXXX")
trap 'rm -f "$tmp"' EXIT
dc() {
  docker compose -p spliteasy --env-file "$root/.env" --env-file "$release/release.env" \
    -f "$release/compose.prod.yml" "$@"
}
dc exec -T postgres pg_dump -U spliteasy -d spliteasy --format=custom > "$tmp"
[[ -s "$tmp" ]]
dc exec -T postgres pg_restore --list < "$tmp" > /dev/null
backup="$root/backups/backup-$(date -u +%Y%m%dT%H%M%SZ)-${tmp##*.}.dump"
mv "$tmp" "$backup"
if [[ -n ${BACKUP_S3_URI:-} ]]; then
  # Configure a private bucket in Mumbai and scoped credentials separately; never put keys in Git.
  aws s3 cp "$backup" "${BACKUP_S3_URI%/}/$(basename "$backup")" --sse AES256 --only-show-errors
fi
echo "Verified database backup: $backup"
