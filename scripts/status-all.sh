#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== parent =="
git -C "$ROOT_DIR" status --short --branch
echo

echo "== remotes =="
git -C "$ROOT_DIR" remote -v
