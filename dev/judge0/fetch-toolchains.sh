#!/bin/bash
# Optional image-build inputs only. Normal startup downloads the prebuilt image.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p vendor
curl --fail --location --retry 3 --output vendor/Python-3.12.16.tar.xz \
  https://www.python.org/ftp/python/3.12.16/Python-3.12.16.tar.xz
curl --fail --location --retry 3 --output vendor/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz \
  https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz
cd vendor
if command -v shasum >/dev/null; then shasum -a 256 -c checksums.txt; else sha256sum -c checksums.txt; fi
