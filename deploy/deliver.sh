#!/usr/bin/env bash
# Called by the serialized, main-only GitHub production job.
set -Eeuo pipefail
umask 077
[[ ${GITHUB_REF:-} == refs/heads/main ]] || { echo 'Only main deploys.'; exit 0; }
[[ "$GITHUB_SHA" =~ ^[a-f0-9]{40}$ ]]
head=$(git ls-remote origin refs/heads/main | cut -f1)
[[ "$head" == "$GITHUB_SHA" ]] || { echo 'Superseded main revision; skipping both deployments.'; exit 0; }
[[ "$SSH_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]]
[[ "$SSH_USER" =~ ^[a-z_][a-z0-9_-]*$ ]]
[[ "$GITHUB_RUN_ID" =~ ^[0-9]+$ && "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]]
[[ "$BACKEND_IMAGE" =~ ^ghcr.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]]
[[ "$CLOUDFLARE_ACCOUNT_ID" =~ ^[a-f0-9]{32}$ ]]
[[ "$CLOUDFLARE_PAGES_PROJECT" =~ ^[a-z0-9][a-z0-9-]*$ ]]
[[ -n "$SSH_KEY" && -n "$KNOWN_HOSTS" && -n "$CLOUDFLARE_API_TOKEN" ]]
pages=${PAGES_DIR:-frontend/dist/frontend/browser}
wrangler=${WRANGLER_BIN:-frontend/node_modules/.bin/wrangler}
[[ -f "$pages/index.html" && -x "$wrangler" ]]
key="$RUNNER_TEMP/lightsail-key"
hosts="$RUNNER_TEMP/lightsail-known-hosts"
trap 'rm -f "$key" "$hosts"' EXIT
printf '%s\n' "$SSH_KEY" > "$key"
printf '%s\n' "$KNOWN_HOSTS" > "$hosts"
printf 'BACKEND_IMAGE=%s\n' "$BACKEND_IMAGE" > release.env
tar -czf "$RUNNER_TEMP/release.tar.gz" compose.prod.yml release.env deploy/Caddyfile deploy/deploy.sh deploy/backup.sh
options=(-i "$key" -o "UserKnownHostsFile=$hosts" -o StrictHostKeyChecking=yes -o BatchMode=yes -o ConnectTimeout=10)
target="$SSH_USER@$SSH_HOST"
remote="/opt/spliteasy/releases/$GITHUB_SHA-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
# Expand only the validated release path; reruns never overwrite an active package.
# shellcheck disable=SC2029
ssh "${options[@]}" "$target" "mkdir '$remote'"
scp "${options[@]}" "$RUNNER_TEMP/release.tar.gz" "$target:$remote/release.tar.gz"
# shellcheck disable=SC2029
ssh "${options[@]}" "$target" "tar -xzf '$remote/release.tar.gz' -C '$remote' && bash '$remote/deploy/deploy.sh'"

# This runs only after the backend and its public tunnel health check succeed.
if ! "$wrangler" pages deploy "$pages" --project-name="$CLOUDFLARE_PAGES_PROJECT" --branch=main --commit-hash="$GITHUB_SHA"; then
  echo 'Pages upload failed; the healthy backend remains deployed. Retry this workflow; do not restore the database.' >&2
  exit 1
fi
for path in /login /groups/00000000-0000-0000-0000-000000000000; do
  curl --fail --silent --show-error --connect-timeout 5 --max-time 20 --retry 5 --retry-all-errors --retry-delay 3 \
    "https://split-easy.io$path" | grep -q '<app-root'
done
echo 'Backend and frontend deployment checks passed.'
