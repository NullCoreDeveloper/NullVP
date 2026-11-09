# NullVP — Modern Multiplatform VPN Client

<p align="center">
  <strong>Современный мультиплатформенный VPN-клиент нового поколения с адаптивным интерфейсом и поддержкой нестандартных маскировочных протоколов.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%20%7C%20Windows%20%7C%20Linux%20%7C%20macOS-blue?style=flat-square" alt="Platforms" />
  <img src="https://img.shields.io/badge/UI-Compose%20Multiplatform-purple?style=flat-square" alt="Compose Multiplatform" />
  <img src="https://img.shields.io/badge/Core-Go%20%2B%20Xray%20%2B%20Wintun-00ADD8?style=flat-square" alt="Core" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-green?style=flat-square" alt="License" />
  <img src="https://komarev.com/ghpvc/?username=NullCoreDeveloper-NullVP&label=Views&color=00ADD8&style=flat-square" alt="Views" />
</p>

---

## 🌟 Основные возможности

### 🚀 Поддерживаемые протоколы и транспорты

#### 1. Стандартные цензуроустойчивые протоколы
* **VLESS**: Reality (Vision / TLS / uTLS), SplitHTTP, gRPC, WebSocket.
* **Trojan**: TLS, WebSocket, gRPC.
* **Shadowsocks**: современные шифры 2022-blake3, AEAD, SIP002.
* **Hysteria 2**: скоростной QUIC-туннель с обфускацией Salamander (`obfs=salamander`).

#### 2. Нестандартные маскировочные транспорты (Covert Transports)
* **VK Calls TURN Relay (`vkturn://`)**:
  * Туннелирование сетевого трафика через аудио/видео релеи ВКонтакте (WebRTC TURN).
  * Встроенный автономный WebView-решатель капчи VK внутри приложения.
  * Автоматическая смена анонимных сессий, User-Agent и TLS-отпечатков браузера (`Chrome 146`, `Firefox 133`) при исчерпании лимитов.
* **MasterDnsVPN (`mdns://`)**:
  * Скрытый туннель поверх стандартных DNS-запросов (UDP 53).
  * Контроль доставки пакетов (ARQ), синхронизация очередей и дублирование пакетов для максимальной надёжности.
  * Пул из 18 независимых DNS-резолверов с авто-переключением при деградации скорости.
* **Mail.ru Docs Transport (`mailru://`)**:
  * Туннелирование через доверенные API и облачную инфраструктуру Mail.ru.
* **Avito & CupsOnline Transports (`openflux://`)**:
  * Инкапсуляция потоков данных в легитимный веб-трафик интерактивных сервисов.

---

## 🛡️ Умная маршрутизация и защита от блокировок

* **Обход сервисов РФ (Smart Bypass RU & LAN)**:
  * Трафик российских банков, государственных порталов и сервисов доставки направляется напрямую по базам `geoip:ru` и `geosite:category-gov-ru/banks`.
  * Локальные сети (192.168.x, 10.x, 127.x) не затрагиваются и работают на полной скорости.
* **Динамический сканер приложений РФ (Android)**:
  * Автоматическое исключение приложений банков и Госуслуг из туннеля.
  * Гарантированный пропуск заблокированных ресурсов через VPN (например, клиент 4PDA всегда идёт через туннель).
* **Умный белый список (Smart Whitelist)**:
  * Режим работы, при котором через прокси направляются только заблокированные ресурсы, а весь чистый интернет идёт напрямую.
* **Пользовательские правила**:
  * Создание гибких правил маршрутизации по доменам (`geosite:...`) и IP-диапазонам (`geoip:...`, CIDR).

---

## 💻 Платформенные особенности

* **Android**:
  * Нативный системный `VpnService` на базе легковесного и быстрого `libv2ray.aar`.
  * Раздельное туннелирование (Per-App Split Tunneling) — выбор конкретных приложений для работы через VPN.
  * Встроенный сканер QR-кодов с поддержкой всех схем подключения.
  * Авто-переподключение при смене сети (Wi-Fi ↔ Mobile).
* **Windows**:
  * Высокопроизводительный виртуальный адаптер на базе официального драйвера **Wintun**.
  * Сплит-маршруты `/1` (`0.0.0.0/1` и `128.0.0.0/1`) без разрыва связи с шлюзом по умолчанию и локальной сетью.
  * Портативная версия (Portable ZIP) со встроенным JRE — запуск без установки в систему.
* **Linux**:
  * Поддержка режимов TUN-интерфейса и системного прокси без необходимости root-прав.
  * Готовые пакеты `.deb`, автономный `.AppImage` и портативный `tar.gz`.
  * Интеграция с системным треем (GNOME, KDE Plasma, XFCE).
* **macOS**:
  * Готовый `.dmg` образ для Apple Silicon и Intel.

---

## 🎨 Интерфейс

* Построен на **Compose Multiplatform (Material 3)**.
* **Темы оформления**: Тёмная (Dark), Светлая (Light) и Системная (System) с мгновенным переключением.
* **Мониторинг соединения**:
  * Графики скорости отдачи/приёма (RX / TX) в реальном времени.
  * Умный пинг с замером задержки через HTTP 204 (`generate_204`).
* **Журнал событий (Logs)**:
  * Детализированные логи состояния ядра с удобной прокруткой и фильтрацией.

---

## 📥 Загрузка и установка

Готовые скомпилированные релизы под все поддерживаемые операционные системы доступны на странице **[Releases](../../releases)**:

| Платформа | Формат сборки | Описание |
| :--- | :--- | :--- |
| **Android** | `.apk` | Универсальный релизный APK (ARM64, ARMv7, x86_64) |
| **Windows** | `.msi` / `.zip` | Установщик Windows Installer и портативный архив со встроенным Wintun |
| **Linux** | `.AppImage` / `.deb` / `.tar.gz` | Автономный запуск в 1 клик и пакет для Debian/Ubuntu |
| **macOS** | `.dmg` | Образ для macOS |

---

## 🔨 Сборка из исходников

### Требования
* **JDK 17** или новее (рекомендуется Eclipse Temurin 17).
* **Android SDK** (API level 35, Build-tools 35.0.0) — для Android.

### Сборка Android
```bash
./gradlew :composeApp:assembleRelease
```

### Сборка Desktop (Linux / Windows / macOS)
```bash
# Создание дистрибутива приложения
./gradlew :composeApp:createDistributable

# Сборка пакетов для текущей ОС
./gradlew :composeApp:packageAppImage :composeApp:packageDeb   # Linux
./gradlew :composeApp:packageMsi                              # Windows
./gradlew :composeApp:packageDmg                              # macOS
```

---

## 📄 Лицензия

Проект распространяется под лицензией [Apache License 2.0](LICENSE).

