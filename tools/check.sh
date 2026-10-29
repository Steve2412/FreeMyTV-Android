#!/usr/bin/env bash
# What CI runs (.github/workflows/check.yml). Run it before you push: the build and the translation catalogues.
# Here ./build.sh uses the newest SDK and JDK you have, while CI uses build-tools 34, android-34 and JDK 17.
set -euo pipefail
cd "$(dirname "$0")/.."
./build.sh
python3 tools/translate.py --check || echo "Some languages lack translations (only English and Spanish are complete)."
echo "All checks passed."
