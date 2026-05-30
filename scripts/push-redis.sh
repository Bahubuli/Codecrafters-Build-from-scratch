#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$ROOT_DIR/codecrafters-redis-java"

if [[ -n "$(git -C "$PROJECT_DIR" status --porcelain)" ]]; then
  echo "Commit or stash changes in codecrafters-redis-java before pushing." >&2
  git -C "$PROJECT_DIR" status --short
  exit 1
fi

codecrafters_remote="$(
  git -C "$PROJECT_DIR" remote -v |
    awk '$2 ~ /git\.codecrafters\.io/ && $3 == "(push)" { print $1; exit }'
)"

if [[ -z "$codecrafters_remote" ]]; then
  echo "No CodeCrafters remote found for codecrafters-redis-java." >&2
  echo "Add the remote from your CodeCrafters setup page, then run again." >&2
  exit 1
fi

git -C "$PROJECT_DIR" push "$codecrafters_remote" master

github_remote="$(
  git -C "$PROJECT_DIR" remote -v |
    awk '$2 ~ /github\.com/ && $3 == "(push)" { print $1; exit }'
)"

if [[ -n "$github_remote" ]]; then
  git -C "$PROJECT_DIR" push "$github_remote" master
fi

echo
echo "Redis child pushed. Now update and push the parent pointer:"
echo "  git add codecrafters-redis-java"
echo "  git commit -m \"Update Redis project pointer\""
echo "  git push origin master"
