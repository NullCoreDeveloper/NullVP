#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Автоматический обход ограничений FUSE для AppImage
export APPIMAGE_EXTRACT_AND_RUN=1

if [ -f "$DIR/dist/NullVP-v1.0.0-linux-x86_64.AppImage" ]; then
    exec "$DIR/dist/NullVP-v1.0.0-linux-x86_64.AppImage" "$@"
elif [ -x "$DIR/composeApp/build/compose/binaries/main/app/NullVP/bin/NullVP" ]; then
    exec "$DIR/composeApp/build/compose/binaries/main/app/NullVP/bin/NullVP" "$@"
else
    echo "Ошибка: исполняемый файл NullVP не найден. Выполните ./build-appimage.sh для сборки."
    exit 1
fi

