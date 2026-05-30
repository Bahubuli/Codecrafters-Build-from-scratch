#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$ROOT_DIR/codecrafters-redis-java"
REMOTE_NAME="${CODECRAFTERS_REDIS_REMOTE:-redis-codecrafters}"
COMMIT_MESSAGE="${1:-Sync Redis from monorepo}"

if [[ -n "$(git -C "$ROOT_DIR" status --porcelain)" ]]; then
  echo "Commit or stash parent repo changes before submitting to CodeCrafters." >&2
  git -C "$ROOT_DIR" status --short
  exit 1
fi

remote_url="$(git -C "$ROOT_DIR" remote get-url "$REMOTE_NAME" 2>/dev/null || true)"
if [[ -z "$remote_url" ]]; then
  echo "No '$REMOTE_NAME' remote found in the parent repo." >&2
  echo "Add it with: git remote add $REMOTE_NAME <codecrafters-git-url>" >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

git clone --branch master --single-branch "$remote_url" "$tmp_dir/redis"
rsync -a --delete --exclude .git "$PROJECT_DIR"/ "$tmp_dir/redis"/

if [[ -z "$(git -C "$tmp_dir/redis" status --porcelain)" ]]; then
  echo "No Redis changes to submit to CodeCrafters."
  exit 0
fi

git -C "$tmp_dir/redis" add -A
git -C "$tmp_dir/redis" commit -m "$COMMIT_MESSAGE"
git -C "$tmp_dir/redis" push origin master
