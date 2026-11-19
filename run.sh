#!/usr/bin/env bash
# 사용법:
#   ./run.sh            -> TestTemp 를 build/TestTemp-work 로 복사해서 그 복사본으로 실행 (원본 안 바뀜)
#                          TestTemp 가 없으면 test/sample/exce 복사본으로 실행
#   ./run.sh <폴더>      -> 그 폴더로 바로 실행 (저장하면 진짜로 바뀜)
set -e
cd "$(dirname "$0")"
[ -f dist/IniEditor.jar ] || ./build.sh
ROOT="$1"
if [ -z "$ROOT" ]; then
  SRC="TestTemp"
  [ -d "$SRC" ] || SRC="test/sample/exce"
  if [ ! -d "$SRC" ]; then
    echo "테스트 폴더(TestTemp, test/sample/exce)가 없어. 사용법: ./run.sh <루트폴더>"
    exit 1
  fi
  rm -rf build/TestTemp-work && mkdir -p build && cp -R "$SRC" build/TestTemp-work
  ROOT="build/TestTemp-work"
  echo "[RUN] $SRC 복사본 → $ROOT"
else
  echo "[RUN] $ROOT"
fi
java -jar dist/IniEditor.jar "$ROOT"
