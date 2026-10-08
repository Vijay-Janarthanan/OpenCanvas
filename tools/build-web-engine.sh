#!/usr/bin/env bash
# Rebuilds docs/engine/opencanvas-engine.mjs: the Kotlin sync engine compiled to JavaScript and bundled
# into one minified ES module that the docs page imports.
#
# Needs JDK 17 and Node.js on the PATH. Run from the repository root:
#
#   tools/build-web-engine.sh
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :packages:opencanvas-core:jsBrowserProductionLibraryDistribution -q

dist=packages/opencanvas-core/build/dist/js/productionLibrary
mkdir -p docs/engine
npx --yes esbuild@0.24.0 "$dist/OpenCanvas-packages-opencanvas-core.mjs" \
  --bundle --minify --format=esm --target=es2020 --outfile=docs/engine/opencanvas-engine.mjs

ls -l docs/engine/opencanvas-engine.mjs
