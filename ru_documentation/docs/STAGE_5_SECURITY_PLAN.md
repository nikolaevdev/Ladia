# План безопасности Stage 5

## 1. Цель Stage 5

Stage 5 добавляет поверх уже существующей сети полноценный security-layer, не ломая работающий mesh transport.
Задача этапа — превратить проект из «децентрализованного мессенджера с trust-механиками» в **децентрализованный мессенджер с реальной конфиденциальностью payload**.

Базовые цели:
- identity keys для каждого узла;
- deriving session keys для каждого peer;
- шифрование сообщений;
- шифрование файлов и медиа в виде secure containers;
- стеганографическая упаковка secure payload;
- полная совместимость с существующим relay/multihop pipeline.

## 2. Текущая база перед Stage 5

До начала Stage 5 в проекте уже есть:
- рабочая direct- и relay-коммуникация;
- mesh recovery и rerouting;
- групповые чаты;
- file relay;
- voice/video functionality;
- diagnostics;
- логика trust state;
- `DeviceIdentityManager`;
- fingerprint / SAS;
- signed handshake;
- `StegoTrustCapsuleManager` как groundwork-компонент.

Это важно, потому что Stage 5 не начинает безопасность с нуля, а использует уже существующую identity/trust-основу.

## 3. Ограничения проектирования

При реализации Stage 5 нельзя:
- ломать `LadyaNodeRepository`;
- ломать `PacketEnvelope`;
- ломать routing;
- ломать relay pipeline;
- переписывать mesh transport с нуля.

Нужно:
- добавлять security поверх текущей сети;
- делать изменения модульно;
- сохранять компилируемость после каждого подэтапа;
- обеспечивать обратную совместимость на переходный период.

## 4. Рекомендуемый набор модулей Stage 5

### `security/IdentityKeyManager.kt`
Назначение:
- генерация identity key pair;
- хранение ключей в Android Keystore;
- выдача public key;
- выдача fingerprint;
- базовая интеграция с существующей trust-моделью.

### `security/SessionKeyManager.kt`
Назначение:
- deriving shared secret для пары узлов;
- deriving session key через HKDF;
- кэширование peer-scoped session keys;
- безопасная передача результата в encryption layer.

### `security/SecurePayloadCipher.kt`
Назначение:
- AES-GCM шифрование и расшифрование payload;
- работа с nonce, ciphertext и auth tag;
- единая API-точка для secure messages и secure containers.

### `security/SecureContainer.kt`
Назначение:
- единая типизированная модель secure container;
- поддержка `secure_message`, `secure_file`, `secure_voice`, `secure_image`;
- sender metadata, timestamp, payload type, encrypted payload.

### `security/SecureContainerCodec.kt`
Назначение:
- кодирование/декодирование secure container;
- совместимость с envelope payload;
- подготовка данных для persistence и stego wrapper.

### `security/StegoTransportManager.kt`
Назначение:
- упаковка secure container в carrier-файл;
- распаковка secure container из carrier;
- MVP через image LSB;
- при наличии времени — расширение на audio phase encoding.

### `security/FingerprintQrCodec.kt`
Назначение:
- экспорт fingerprint/public key info в QR;
- импорт/сравнение;
- UX-основа для verify identity flow.

## 5. Поэтапный план реализации

## Stage 5.1 — Identity keys

### Цель
Создать менеджер identity keys, который не ломает текущую сеть и подготавливает криптографическую основу для следующих этапов.

### Обязательный результат
Новый компонент `IdentityKeyManager`, который:
- генерирует key pair при первом запуске;
- хранит private key в Android Keystore;
- отдаёт public key;
- отдаёт fingerprint;
- может быть подключён к profile/trust UI.

### Рекомендации по интеграции
На этом шаге не нужно переписывать весь `DeviceIdentityManager`.
Правильнее:
- добавить новый менеджер рядом;
- сделать адаптер или временный bridge;
- использовать новый ключ сначала для display/verification logic.

### Безопасная стратегия внедрения
1. Создать `IdentityKeyManager`.
2. Подключить его без удаления старых identity-механик.
3. Сначала использовать только для показа профиля/fingerprint.
4. Позже связать его с deriving session keys.

## Stage 5.2 — Session keys

### Цель
Создать общий секрет для пары узлов, используя локальный private identity key и удалённый public key.

### Целевая схема
- key agreement: `X25519`
- KDF: `HKDF`

### Ожидаемый поток
`sharedSecret = X25519(localPrivateKey, remotePublicKey)`  
`sessionKey = HKDF(sharedSecret)`

### Ограничения
- session keys должны быть привязаны к конкретному peer;
- relay-узлам они не нужны;
- routing менять нельзя;
- кэширование должно быть аккуратным, чтобы не вычислять ключ слишком часто.

## Stage 5.3 — Secure messages

### Цель
Шифровать текстовый payload перед отправкой.

### Целевая схема
- `AES-GCM-256`

### Перед отправкой
`plaintext -> encrypt -> nonce + ciphertext + authTag`

### При получении
`nonce + ciphertext + authTag -> decrypt -> plaintext`

### Важная заметка
Шифровать нужно только payload.
Envelope должен оставаться видимым для transport/routing-слоя.

### Ожидаемая интеграция с envelope
Существующий envelope может содержать metadata вроде:
- `payloadEncryption = E2EE`
- `payloadType = secure`

Сам secure container при этом размещается внутри `payload`.

## Stage 5.4 — Secure file containers

### Цель
Передавать файлы как шифрованные контейнеры, а не как plain transfer payload.

### Предлагаемый формат контейнера
`LADYA_SECURE_FILE`

Предлагаемые поля:
- magic
- version
- senderPeerId
- timestamp
- originalName / mimeType metadata
- nonce
- ciphertext
- authTag

### Важный принцип
Получатель должен восстанавливать исходный файл только после успешной расшифровки и проверки целостности.

## Stage 5.5 — Secure typed containers

### Поддерживаемые типы payload
- `secure_message`
- `secure_file`
- `secure_voice`
- `secure_image`

### Единая идея контейнера
Каждый secure container должен содержать:
- sender peer id;
- timestamp;
- payload type;
- encrypted payload;
- при необходимости metadata.

Это унифицирует direct delivery, relay delivery, persistence и future stego wrapping.

## Stage 5.6 — Стеганографический транспорт

### Цель
Разрешить selected secure payload передаваться как внешне обычные медиафайлы.

### Предлагаемые методы
#### Изображения
- LSB steganography

#### Аудио
- phase encoding

### Практический MVP-совет
Для первого рабочего шага:
- начать только с image LSB;
- поддерживать небольшие secure payload;
- audio stego добавить позже, если останется время.

### Важная проектная заметка
Стеганография должна оборачивать **secure container**, а не сырой plaintext.
Иначе стего будет скрывать форму трафика, но не обеспечивать конфиденциальность содержимого.

## Stage 5.7 — Совместимость с mesh

### Цель
Гарантировать, что relay-узлы пересылают только непрозрачные encrypted blob.

### Что должен видеть relay
- только outer packet envelope;
- sender/target packet metadata;
- TTL / ack data;
- возможно, marker типа secure payload.

### Чего relay видеть не должен
- plaintext текста;
- содержимого файлов после расшифровки;
- расшифрованных voice/image payload.

## 6. Расширение диагностики

Нужно добавить следующие счётчики:
- `encryptedPacketsSent`
- `encryptedPacketsReceived`
- `decryptionErrors`
- `stegoPacketsDetected`

Дополнительно полезны:
- `secureContainersCreated`
- `secureContainersOpened`
- `sessionKeysActive`
- `identityVerificationFailures`

## 7. Изменения UI

### Экран профиля
Добавить:
- identity fingerprint;
- полный fingerprint публичного ключа;
- действие verify identity;
- экспорт/импорт QR для верификации.

### Экран соединения
Добавить:
- индикацию, зашифрована ли peer-session;
- состояние верификации ключа;
- при необходимости индикатор активной secure-session.

### Экран чата
Возможные поздние улучшения:
- badge защищённого сообщения;
- badge stego-сообщения;
- системный bubble для decryption error.

## 8. Соображения по persistence

Нужно явно решить, что хранится локально:
- только decrypted plaintext после успешного открытия;
- secure blob плюс metadata;
- либо обе формы.

Рекомендуемый MVP-подход:
- хранить human-readable сообщение после локальной расшифровки для непрерывности UI;
- хранить достаточно metadata, чтобы помечать сообщение как originally secured;
- не хранить долгоживущие session keys в SQLite.

## 9. Рекомендуемый порядок реализации

1. `IdentityKeyManager`
2. adapter layer к текущей identity-flow
3. `SessionKeyManager`
4. `SecurePayloadCipher`
5. secure send/receive для текстовых сообщений
6. codec для secure container
7. secure file container
8. diagnostics counters
9. QR-verification в профиле
10. image stego wrapping
11. audio stego wrapping, если останется время

## 10. Риски

### Риск 1 — сломать текущий handshake
Митигирующее действие:
- сохранить compatibility-path, пока новая identity-handling не доказана на практике.

### Риск 2 — сломать relay-совместимость
Митигирующее действие:
- не трогать routing logic;
- оборачивать только содержимое payload.

### Риск 3 — слишком большой stego payload
Митигирующее действие:
- ввести жёсткий лимит размера;
- показывать явную UI-ошибку, если carrier не может вместить данные.

### Риск 4 — смешение текущей identity-модели с новой
Митигирующее действие:
- вводить новый security-layer поэтапно;
- использовать bridge-слой;
- явно разделять legacy identity и keystore-backed identity на переходном периоде.

## 11. Рекомендуемый первый deliverable

### Deliverable
`IdentityKeyManager.kt`

### Что он должен делать
- генерировать key pair;
- хранить private key в Android Keystore;
- возвращать public key;
- возвращать fingerprint;
- быть подключаемым без поломки текущего mesh transport.
