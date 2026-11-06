#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$ROOT_DIR/composeApp/build/compose/binaries/main"
APP_DIR="$BUILD_DIR/app/NullVP"
APPIMAGE_BUILD_DIR="/tmp/nullvp-appimage-build/AppDir"
RUNTIME_FILE="${HOME}/.local/lib/appimagetool/runtime-x86_64"
DIST_DIR="$ROOT_DIR/dist"

echo "=== 1. Сборка Go ядра nullvp-core ==="
mkdir -p "$ROOT_DIR/core-bridge/bin"
pushd "$ROOT_DIR/core-bridge" >/dev/null
go build -trimpath -ldflags="-s -w" -o bin/nullvp-core ./cmd/main.go
popd >/dev/null

echo "=== 2. Сборка Compose Desktop Distributable ==="
pushd "$ROOT_DIR" >/dev/null
./gradlew createDistributable
popd >/dev/null

echo "=== 3. Подготовка структуры AppDir ==="
rm -rf "$APPIMAGE_BUILD_DIR"
mkdir -p "$APPIMAGE_BUILD_DIR"
cp -a "$APP_DIR"/* "$APPIMAGE_BUILD_DIR"/

# Копируем демон ядра
mkdir -p "$APPIMAGE_BUILD_DIR/bin"
cp "$ROOT_DIR/core-bridge/bin/nullvp-core" "$APPIMAGE_BUILD_DIR/bin/"
chmod +x "$APPIMAGE_BUILD_DIR/bin/nullvp-core"

# Копируем геобазы и нативные библиотеки трея
mkdir -p "$APPIMAGE_BUILD_DIR/lib/app/resources/assets"
mkdir -p "$APPIMAGE_BUILD_DIR/lib/app/resources/composetray"
if [ -d "$ROOT_DIR/composeApp/src/desktopMain/resources/assets" ]; then
    cp "$ROOT_DIR/composeApp/src/desktopMain/resources/assets"/*.dat "$APPIMAGE_BUILD_DIR/lib/app/resources/assets/" 2>/dev/null || true
fi
if [ -d "$ROOT_DIR/composeApp/src/desktopMain/resources/composetray" ]; then
    cp -r "$ROOT_DIR/composeApp/src/desktopMain/resources/composetray"/* "$APPIMAGE_BUILD_DIR/lib/app/resources/composetray/" 2>/dev/null || true
fi

# Создаем AppRun
cat << 'EOF' > "$APPIMAGE_BUILD_DIR/AppRun"
#!/bin/sh
HERE="$(dirname "$(readlink -f "${0}")")"
export APPDIR="${APPDIR:-$HERE}"
export PATH="$HERE/bin:$PATH"
export LD_LIBRARY_PATH="$HERE/lib/app:$HERE/lib/app/resources/composetray/native/linux-x64:$LD_LIBRARY_PATH"
exec "$HERE/bin/NullVP" "$@"
EOF
chmod +x "$APPIMAGE_BUILD_DIR/AppRun"

# Создаем Desktop файл
cat << 'EOF' > "$APPIMAGE_BUILD_DIR/nullvp.desktop"
[Desktop Entry]
Type=Application
Name=NullVP
Exec=NullVP %u
Icon=nullvp
Categories=Network;
Comment=NullVP Cross-Platform Modern VPN Client
Terminal=false
EOF

# Иконка
cp "$ROOT_DIR/composeApp/src/desktopMain/resources/icon.png" "$APPIMAGE_BUILD_DIR/nullvp.png"
ln -sf nullvp.png "$APPIMAGE_BUILD_DIR/.DirIcon"

echo "=== 4. Упаковка в AppImage ==="
mkdir -p "$DIST_DIR"
OUTPUT_APPIMAGE="$DIST_DIR/NullVP-v1.0.0-linux-x86_64.AppImage"
rm -f "$OUTPUT_APPIMAGE"

APPIMAGETOOL_BIN="$(command -v appimagetool || echo "${HOME}/.local/bin/appimagetool")"
RUNTIME_ARG=""
if [ -f "$RUNTIME_FILE" ]; then
    RUNTIME_ARG="--runtime-file $RUNTIME_FILE"
fi

ARCH=x86_64 "$APPIMAGETOOL_BIN" --no-appstream $RUNTIME_ARG "$APPIMAGE_BUILD_DIR" "$OUTPUT_APPIMAGE"
chmod +x "$OUTPUT_APPIMAGE"

echo "=== 5. Создание портативного tar.gz и копирование Android APK ==="
if [ -f "$ROOT_DIR/composeApp/build/outputs/apk/debug/composeApp-debug.apk" ]; then
    cp -f "$ROOT_DIR/composeApp/build/outputs/apk/debug/composeApp-debug.apk" "$DIST_DIR/NullVP-v1.0.0-android.apk"
fi
tar -czf "$DIST_DIR/NullVP-v1.0.0-linux-x86_64.tar.gz" -C "$BUILD_DIR/app" NullVP

echo "=== Готово! Релизные файлы в $DIST_DIR: ==="
ls -lh "$DIST_DIR"
