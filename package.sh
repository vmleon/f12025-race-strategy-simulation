#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Read version
if [[ ! -f VERSION ]]; then
  echo "ERROR: VERSION file not found" >&2
  exit 1
fi

VERSION=$(tr -d '[:space:]' < VERSION)

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: '$VERSION' is not valid semver (expected X.Y.Z)" >&2
  exit 1
fi

# Check for matching git tag
TAG="v$VERSION"
if ! git tag -l "$TAG" | grep -q "$TAG"; then
  echo "WARNING: git tag '$TAG' does not exist. Consider running: git tag $TAG" >&2
fi

ZIP_NAME="tfg-victormartinalvarez-${VERSION}.zip"

# Remove previous zip with same name
rm -f "$ZIP_NAME"

zip -r "$ZIP_NAME" \
  backend/ \
  calibration/ \
  client/ \
  database/ \
  portal/ \
  simulator/ \
  telemetry/ \
  manage.py \
  requirements.txt \
  .gitignore \
  README.md \
  LOCAL_SETUP.md \
  VERSION \
  -x "**/.gradle/*" \
  -x "**/build/*" \
  -x "**/bin/*" \
  -x "**/node_modules/*" \
  -x "**/dist/*" \
  -x "**/.angular/*" \
  -x "**/__pycache__/*" \
  -x "**/.pytest_cache/*" \
  -x "**/*.pyc" \
  -x "**/*.class" \
  -x "**/venv/*" \
  -x "**/.venv/*" \
  -x "**/.DS_Store" \
  -x "**/.env"

ZIP_SIZE=$(du -h "$ZIP_NAME" | cut -f1)
echo ""
echo "Created $ZIP_NAME ($ZIP_SIZE)"
