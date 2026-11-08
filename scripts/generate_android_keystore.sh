#!/usr/bin/env bash
# ==============================================================================
# NullVP Android Keystore Generator & GitHub Secrets Helper
# ==============================================================================
set -e

KEYSTORE_NAME="nullvp-release.keystore"
KEY_ALIAS="nullvp_release_key"
VALIDITY_DAYS=10000

echo "=== Генерация ключа подписи Android для NullVP ==="
echo "Этот скрипт сгенерирует production-ключ подписи и выведет значения для GitHub Secrets."
echo ""

# Генерируем случайный надежный пароль если не задан
PASS=$(openssl rand -base64 18 | tr -dc 'a-zA-Z0-9' | head -c 16)

echo "Создание хранилища ключей: $KEYSTORE_NAME (алиас: $KEY_ALIAS)..."
keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_NAME" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -storepass "$PASS" \
    -keypass "$PASS" \
    -dname "CN=NullVP Developer, OU=NullVP Project, O=NullVP, L=World, ST=Cyberspace, C=UN"

echo ""
echo "=== Кодирование в Base64 для GitHub Secrets ==="
KEYSTORE_BASE64=$(base64 -w 0 "$KEYSTORE_NAME" 2>/dev/null || base64 "$KEYSTORE_NAME")

echo "Успешно сгенерировано!"
echo "------------------------------------------------------------------"
echo "Добавьте следующие 4 секрета в GitHub репозиторий:"
echo "Settings -> Secrets and variables -> Actions -> New repository secret"
echo "------------------------------------------------------------------"
echo ""
echo "1) ANDROID_KEYSTORE_BASE64:"
echo "$KEYSTORE_BASE64"
echo ""
echo "2) ANDROID_KEYSTORE_PASSWORD:"
echo "$PASS"
echo ""
echo "3) ANDROID_KEY_ALIAS:"
echo "$KEY_ALIAS"
echo ""
echo "4) ANDROID_KEY_PASSWORD:"
echo "$PASS"
echo ""
echo "------------------------------------------------------------------"
echo "ВАЖНО: Файл $KEYSTORE_NAME добавлен в .gitignore и НЕ должен попадать в git-репозиторий!"

