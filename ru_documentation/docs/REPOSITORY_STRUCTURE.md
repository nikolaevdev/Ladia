# Структура репозитория

## Корневой уровень

### `app/`
Основной Android-модуль приложения.
Содержит исходный код, ресурсы, manifest, конфигурацию Gradle-модуля и тесты.

### `gradle/`
Gradle wrapper и общие настройки сборки.

### `build.gradle.kts`
Корневой Gradle-скрипт проекта.

### `settings.gradle.kts`
Настройки модулей проекта.

### `gradle.properties`
Глобальные Gradle-свойства.

### `gradlew`, `gradlew.bat`
Скрипты запуска Gradle wrapper.

### `libs.versions.toml`
Централизованный каталог версий зависимостей и плагинов.

### Исторические stage-файлы
Файлы вида:
- `README_STAGE_*.md`
- `CHANGELOG_STAGE_*.md`
- `KNOWN_LIMITATIONS_STAGE_*.md`
- `DEMO_STAGE_*.md`

Это покадровый журнал развития проекта по этапам.
Они полезны для подготовки демо, защиты и восстановления истории реализации.

## Структура application-модуля

`app/src/main/java/ru/mishanikolaev/ladya/`

### `MainActivity.kt`
Точка входа в приложение.
Инициализирует repository, уведомления, permissions, foreground service и корневую навигацию.

### `call/`
Слой real-time-коммуникаций.

#### `AudioCallManager.kt`
Отвечает за:
- UDP transport аудио;
- захват микрофона;
- циклы отправки и приёма аудио;
- базовые call counters и сообщения об ошибках.

#### `VideoCallManager.kt`
Отвечает за:
- UDP transport видео;
- обработку кадров камеры;
- облегчённую packetization;
- адаптацию bitrate/profile;
- реконструкцию удалённых кадров.

### `navigation/`
Слой Compose-навигации.

#### `AppNavHost.kt`
Корневой navigation host для всех экранов.

#### `AppRoute.kt`
Перечень маршрутов:
- Chats
- Connection
- Chat
- GroupChat
- Call
- Logs
- AddContact
- Settings
- Profile

### `network/`
Основной сетевой и orchestration-слой.

#### `LadyaNodeRepository.kt`
Центральный класс проекта.
Отвечает за discovery, прямые сессии, relay-логику, группы, файлы, trust, связку с persistence, логи и координацию звонков.

#### `LadyaDatabaseHelper.kt`
SQLite-helper для локального хранения данных.

#### `protocol/`
Протокольные модели и вспомогательные классы.

##### `ProtocolConstants.kt`
Константы протокола, versioning, значения TTL по умолчанию, retry defaults и packet-type constants.

##### `PacketEnvelope.kt`
Модель envelope для сетевых пакетов.

##### `PacketFactory.kt`
Вспомогательные методы для построения исходящих пакетов.

##### `PacketParser.kt`
Вспомогательные методы для разбора входящих пакетов.

##### `TransferManifest.kt`
Схема manifest для передачи файлов.

#### `transport/`
Содержит transport-related helper-модели, например delivery policy и сопутствующие сущности.

### `notify/`
Поддержка уведомлений.

#### `AppNotificationManager.kt`
Создаёт каналы уведомлений и управляет пользовательскими уведомлениями для звонков, сообщений и системных событий.

### `security/`
Основа security и trust-подсистемы.

#### `DeviceIdentityManager.kt`
Отвечает за:
- создание идентичности устройства;
- хранение и загрузку key material;
- подпись handshake payload;
- проверку подписей;
- получение fingerprint;
- вычисление short auth string.

#### `StegoTrustCapsuleManager.kt`
Отвечает за:
- встраивание подписанных trust-метаданных в пиксели изображения с помощью LSB;
- извлечение таких метаданных обратно;
- текущий задел под последующий secure/stego transport.

### `service/`
Поддержка foreground service.

#### `LadyaForegroundService.kt`
Поддерживает жизнь узла приложения во время длительных сессий и фоновой работы.

### `sound/`
Сигнальные и интерфейсные звуки.

#### `AppSoundManager.kt`
Проигрывает пользовательские звуки обратной связи для звонков, сообщений, файлов, событий устройств, предупреждений и уведомлений.

### `ui/`
Compose UI-реализация.

#### `components/`
Переиспользуемые UI-виджеты.
Примеры:
- message bubbles;
- contact cards;
- peer cards;
- log rows;
- file panels;
- avatar-компоненты;
- image dialogs.

#### `models/UiModels.kt`
Файл со всеми основными UI-состояниями и action-моделями.
Это один из важнейших файлов, потому что он документирует пользовательскую state-machine приложения.

Ключевые модели:
- `ConnectionUiState`
- `ChatUiState`
- `CallUiState`
- `ContactsUiState`
- `SettingsUiState`
- `ProfileUiState`
- `ChatGroupDefinition`
- `DiscoveredPeerUi`
- `MeshRouteUi`
- `ContactUi`
- `ChatThreadUi`

#### `screens/`
Экранные Compose-реализации.

##### `screens/chat/`
Экраны чатов и групповых чатов.

##### `screens/call/`
Экран звонка.

##### `screens/connection/`
Экран соединения и сетевой диагностики.

##### `screens/contacts/`
UI для контактов, потоков и найденных устройств.

##### `screens/logs/`
Системные и сетевые логи.

##### `screens/profile/`
Экран локального/удалённого профиля, trust-действия, fingerprint-view.

##### `screens/settings/`
Настройки и конфигурация звуков.

#### `theme/`
Определения Compose-темы.

## Ресурсы

`app/src/main/res/`

### `drawable/`, `mipmap-*`
Иконки приложения и графические ресурсы.

### `drawable-nodpi/`
Брендинговые ассеты, включая логотип Ladya.

### `raw/`
Звуковые ресурсы для UI и signaling-событий.
Примеры:
- входящий звонок;
- входящее сообщение;
- файл получен;
- relay connected/lost;
- verification sounds.

### `xml/`
Android XML-ресурсы, например backup rules и пути file provider.

## Manifest

### `AndroidManifest.xml`
Определяет:
- метаданные приложения;
- permissions;
- foreground service;
- main activity;
- file provider.

Ключевые permissions в текущем снимке:
- Internet
- foreground service
- notifications
- multicast state
- audio settings
- microphone
- camera

## Тесты

### `app/src/test/`
Базовая заглушка для JVM unit test.

### `app/src/androidTest/`
Базовая заглушка для instrumented test.

## Практический порядок чтения для нового разработчика

Если Вы впервые заходите в репозиторий, наиболее эффективен такой порядок чтения:

1. `README.md`
2. `navigation/AppRoute.kt`
3. `ui/models/UiModels.kt`
4. `MainActivity.kt`
5. `network/protocol/PacketEnvelope.kt`
6. `network/LadyaNodeRepository.kt`
7. `call/AudioCallManager.kt`
8. `call/VideoCallManager.kt`
9. `security/DeviceIdentityManager.kt`
10. `security/StegoTrustCapsuleManager.kt`
11. экранные реализации в `ui/screens/`

## Практические рекомендации по модификации

### Если Вы меняете поведение чата
Начинайте с:
- `UiModels.kt`
- `ChatScreen.kt`
- `LadyaNodeRepository.kt`

### Если Вы меняете mesh-логику
Начинайте с:
- `PacketEnvelope.kt`
- частей `LadyaNodeRepository.kt`, связанных с relay
- diagnostic-полей в `ConnectionUiState`

### Если Вы меняете trust/security-поведение
Начинайте с:
- `DeviceIdentityManager.kt`
- profile/trust-логики в `LadyaNodeRepository.kt`
- `ProfileScreen.kt`

### Если Вы меняете media-поведение
Начинайте с:
- `AudioCallManager.kt`
- `VideoCallManager.kt`
- `CallScreen.kt`

### Если Вы реализуете Stage 5
Начинайте с:
- `security/`
- точек packet wrapping в `LadyaNodeRepository.kt`
- UI fingerprint/verification в профиле и connection-экране
- diagnostic counters в `ConnectionUiState`
