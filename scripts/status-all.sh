#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== parent =="
git -C "$ROOT_DIR" status --short --branch
echo

echo "== codecrafters-redis-java =="
git -C "$ROOT_DIR/codecrafters-redis-java" status --short --branch
echo

echo "== child remotes =="
git -C "$ROOT_DIR/codecrafters-redis-java" remote -v
