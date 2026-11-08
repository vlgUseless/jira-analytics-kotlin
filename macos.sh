#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
    echo 'This launcher requires macOS on Apple Silicon.' >&2
    exit 1
fi

jdk=zulu22.32.15-ca-jdk22.0.2-macosx_aarch64
export JAVA_HOME="$PWD/runtime/$jdk/zulu-22.jdk/Contents/Home"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    mkdir -p runtime
    archive="runtime/$jdk.tar.gz"
    if [[ ! -f "$archive" ]]; then
        curl -fL --retry 3 "https://cdn.azul.com/zulu/bin/$jdk.tar.gz" -o "$archive.part"
        mv "$archive.part" "$archive"
    fi
    printf '%s  %s\n' e70f161f2d649cf24cdddc448daa39f3229cb73c509d8c231defef5960177b69 "$archive" | shasum -a 256 -c -
    tar -xzf "$archive" -C runtime
fi
export PATH="$JAVA_HOME/bin:$PATH"
if [[ $# -eq 0 ]]; then
    set -- run --args=config/app.properties
fi
exec bash gradlew "$@"
