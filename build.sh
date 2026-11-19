#!/usr/bin/env bash
# dist/IniEditor.jar 빌드 (Java 8 타깃)
set -e
cd "$(dirname "$0")"
rm -rf build/classes && mkdir -p build/classes dist
# JDK 9+ 이면 --release 8 로 Java 8 API 사용 여부까지 검사, JDK 8 이면 -source/-target
if javac --release 8 -version >/dev/null 2>&1; then
  OPT="--release 8 -Xlint:-options"
else
  OPT="-source 1.8 -target 1.8"
fi
javac -encoding UTF-8 $OPT -d build/classes src/IniEditor.java
jar cfm dist/IniEditor.jar MANIFEST.MF -C build/classes .
echo "[OK] dist/IniEditor.jar"
