#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$ROOT_DIR/composeApp/build/compose/binaries/main"
APP_DIR="$BUILD_DIR/app/NullVP"
APPIMAGE_BUILD_DIR="/tmp/nullvp-appimage-build/AppDir"
RUNTIME_FILE="${HOME}/.local/lib/appimagetool/runtime-x86_64"
DIST_DIR="$ROOT_DIR/dist"

echo "=== 1. Сборка Go ядра nullvp-core ==="
mkdir -p "$ROOT_DIR/core-bridge/bin" "$ROOT_DIR/bin"
pushd "$ROOT_DIR/core-bridge" >/dev/null
go build -trimpath -ldflags="-s -w" -o bin/nullvp-core ./cmd/main.go
cp -f bin/nullvp-core "$ROOT_DIR/bin/nullvp-core"
popd >/dev/null

echo "=== 2. Сборка Compose Desktop Distributable и Android APK ==="
pushd "$ROOT_DIR" >/dev/null
./gradlew createDistributable assembleDebug
popd >/dev/null

echo "=== 3. Подготовка структуры AppDir ==="
rm -rf "$APPIMAGE_BUILD_DIR"
mkdir -p "$APPIMAGE_BUILD_DIR"
cp -a "$APP_DIR"/* "$APPIMAGE_BUILD_DIR"/

# Копируем runtime java если доступен в системе
JAVA_BIN="$(readlink -f /usr/bin/java 2>/dev/null || which java 2>/dev/null || true)"
if [ -n "$JAVA_BIN" ] && [ -x "$JAVA_BIN" ]; then
    mkdir -p "$APPIMAGE_BUILD_DIR/lib/runtime/bin"
    cp -f "$JAVA_BIN" "$APPIMAGE_BUILD_DIR/lib/runtime/bin/java" 2>/dev/null || true
    chmod +x "$APPIMAGE_BUILD_DIR/lib/runtime/bin/java" || true
fi

# Заменяем C++ jpackage лаунчер на надежный скрипт запуска (исправляет pure virtual method crash на Wayland)
cat << 'EOF' > "$APPIMAGE_BUILD_DIR/bin/NullVP"
#!/bin/sh
APPDIR="$(dirname "$(dirname "$(readlink -f "$0")")")"
RUNTIME="$APPDIR/lib/runtime"
if [ -x "$RUNTIME/bin/java" ]; then
    JAVA_EXEC="$RUNTIME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_EXEC="java"
elif [ -x "/usr/lib/jvm/default/bin/java" ]; then
    JAVA_EXEC="/usr/lib/jvm/default/bin/java"
else
    echo "Error: Java runtime not found" >&2
    exit 1
fi
exec "$JAVA_EXEC" \
    -Dfile.encoding=UTF-8 \
    -Dstdout.encoding=UTF-8 \
    -Djdk.gtk.version=3 \
    -Djdk.lang.Process.launchMechanism=vfork \
    -Dapp.dir="$APPDIR" \
    -Dskiko.library.path="$APPDIR/lib/app" \
    -Dcompose.application.resources.dir="$APPDIR/lib/app/resources" \
    -Dcompose.application.configure.swing.globals=true \
    -Djpackage.app-version=1.0.0 \
    -cp "$APPDIR/lib/app/*" \
    nullvp.MainKt \
    "$@"
EOF
chmod +x "$APPIMAGE_BUILD_DIR/bin/NullVP"
mkdir -p "$APP_DIR/bin"
cp -f "$APPIMAGE_BUILD_DIR/bin/NullVP" "$APP_DIR/bin/NullVP"

# Копируем бинарники реальных ядер (xray, tun2socks, nullvp-core)
mkdir -p "$APPIMAGE_BUILD_DIR/bin"
cp -f "$ROOT_DIR/bin/xray" "$APPIMAGE_BUILD_DIR/bin/" 2>/dev/null || true
cp -f "$ROOT_DIR/bin/tun2socks" "$APPIMAGE_BUILD_DIR/bin/" 2>/dev/null || true
cp -f "$ROOT_DIR/bin/nullvp-core" "$APPIMAGE_BUILD_DIR/bin/" 2>/dev/null || cp -f "$ROOT_DIR/core-bridge/bin/nullvp-core" "$APPIMAGE_BUILD_DIR/bin/" 2>/dev/null || true
chmod +x "$APPIMAGE_BUILD_DIR/bin"/* || true

# Копируем геобазы, иконку и нативные библиотеки трея
mkdir -p "$APPIMAGE_BUILD_DIR/assets"
mkdir -p "$APPIMAGE_BUILD_DIR/lib/app/resources/assets"
mkdir -p "$APPIMAGE_BUILD_DIR/lib/app/resources/composetray"
if [ -d "$ROOT_DIR/composeApp/src/desktopMain/resources/assets" ]; then
    cp "$ROOT_DIR/composeApp/src/desktopMain/resources/assets"/*.dat "$APPIMAGE_BUILD_DIR/assets/" 2>/dev/null || true
    cp "$ROOT_DIR/composeApp/src/desktopMain/resources/assets"/*.dat "$APPIMAGE_BUILD_DIR/lib/app/resources/assets/" 2>/dev/null || true
fi
if [ -d "$ROOT_DIR/composeApp/src/desktopMain/resources/composetray" ]; then
    cp -r "$ROOT_DIR/composeApp/src/desktopMain/resources/composetray"/* "$APPIMAGE_BUILD_DIR/lib/app/resources/composetray/" 2>/dev/null || true
fi
cp -f "$ROOT_DIR/composeApp/src/desktopMain/resources/icon.png" "$APPIMAGE_BUILD_DIR/lib/app/resources/icon.png" 2>/dev/null || true

# Создаем AppRun
cat << 'EOF' > "$APPIMAGE_BUILD_DIR/AppRun"
#!/bin/sh
HERE="$(dirname "$(readlink -f "${0}")")"
export APPDIR="${APPDIR:-$HERE}"
export PATH="$HERE/bin:$PATH"
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
