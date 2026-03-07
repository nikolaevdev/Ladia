# Repository structure

## Root level

### `app/`
Main Android application module.
Contains source code, resources, manifest, Gradle module config, and tests.

### `gradle/`
Gradle wrapper and shared build configuration.

### `build.gradle.kts`
Root Gradle build script.

### `settings.gradle.kts`
Project module settings.

### `gradle.properties`
Gradle properties.

### `gradlew`, `gradlew.bat`
Wrapper launchers.

### `libs.versions.toml`
Centralized dependency and plugin version catalog.

### Historical stage files
Files such as:
- `README_STAGE_*.md`
- `CHANGELOG_STAGE_*.md`
- `KNOWN_LIMITATIONS_STAGE_*.md`
- `DEMO_STAGE_*.md`

These represent a stage-by-stage development log of the project.
They are useful for demo preparation and for reconstructing the implementation history.

## Application module structure

`app/src/main/java/ru/mishanikolaev/ladya/`

### `MainActivity.kt`
Application entry point.
Initializes repository, notifications, permissions, foreground service, and root navigation.

### `call/`
Real-time communication layer.

#### `AudioCallManager.kt`
- UDP audio transport
- microphone capture
- audio send/receive loops
- basic call counters and error reporting

#### `VideoCallManager.kt`
- UDP video transport
- camera frame handling
- lightweight packetization
- bitrate/profile adaptation
- remote frame reconstruction

### `navigation/`
Compose navigation layer.

#### `AppNavHost.kt`
Root navigation host for all screens.

#### `AppRoute.kt`
Enum of routes:
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
Core networking and orchestration layer.

#### `LadyaNodeRepository.kt`
The central class of the project.
Handles discovery, direct sessions, relay logic, groups, files, trust, persistence binding, logs, and coordination with calls.

#### `LadyaDatabaseHelper.kt`
SQLite helper for local persistence.

#### `protocol/`
Protocol models and helpers.

##### `ProtocolConstants.kt`
Protocol constants, versioning, TTL defaults, retry defaults, packet type constants.

##### `PacketEnvelope.kt`
Envelope data model for network packets.

##### `PacketFactory.kt`
Helpers for constructing outgoing packets.

##### `PacketParser.kt`
Helpers for parsing incoming packets.

##### `TransferManifest.kt`
Manifest schema for file transfers.

#### `transport/`
Currently contains transport-related helper models such as delivery policy.

### `notify/`
Notification support.

#### `AppNotificationManager.kt`
Creates channels and manages user-visible notifications for calls/messages/system events.

### `security/`
Security and trust groundwork.

#### `DeviceIdentityManager.kt`
- creates device identity;
- stores/loads key material;
- signs handshake payloads;
- verifies signatures;
- derives fingerprints;
- derives short auth string.

#### `StegoTrustCapsuleManager.kt`
- embeds signed trust metadata into image pixels using LSB;
- extracts such metadata back;
- currently used as a groundwork component for later secure/stego transport.

### `service/`
Foreground service support.

#### `LadyaForegroundService.kt`
Keeps the app node alive during longer sessions and background work.

### `sound/`
Sound effects and signaling audio.

#### `AppSoundManager.kt`
Plays user feedback sounds for calls, messages, files, device events, warnings, and notifications.

### `ui/`
Compose UI implementation.

#### `components/`
Reusable UI widgets.
Examples:
- message bubbles;
- contact cards;
- peer cards;
- log rows;
- file panels;
- avatar components;
- image dialogs.

#### `models/UiModels.kt`
All major UI state and action models.
This file is important because it documents the user-visible state machine of the app.

Key models:
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
Screen-level Compose implementations.

##### `screens/chat/`
Chat and group chat screens.

##### `screens/call/`
Call screen.

##### `screens/connection/`
Connection and network diagnostics screen.

##### `screens/contacts/`
Contacts, threads, and discovery-related UI.

##### `screens/logs/`
System and network logs.

##### `screens/profile/`
Local/remote profile screen, trust actions, fingerprint view.

##### `screens/settings/`
Settings and sound configuration.

#### `theme/`
Compose theme definitions.

## Resources

`app/src/main/res/`

### `drawable/`, `mipmap-*`
Application icons and graphics.

### `drawable-nodpi/`
Contains branding assets such as the Ladya logo.

### `raw/`
Sound resources for UI and signaling events.
Examples:
- incoming call;
- message incoming;
- file received;
- relay connected/lost;
- verification sounds.

### `xml/`
Android XML resources such as backup rules and file provider paths.

## Manifest

### `AndroidManifest.xml`
Defines:
- app metadata;
- permissions;
- foreground service;
- main activity;
- file provider.

Key permissions in the current snapshot:
- Internet
- foreground service
- notifications
- multicast state
- audio settings
- microphone
- camera

## Tests

### `app/src/test/`
Basic JVM unit test placeholder.

### `app/src/androidTest/`
Basic instrumented test placeholder.

## Practical reading order for a new developer

If you are new to the repository, the most effective reading order is:

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
11. screen implementations in `ui/screens/`

## Practical modification guidance

### If you are changing chat behavior
Start with:
- `UiModels.kt`
- `ChatScreen.kt`
- `LadyaNodeRepository.kt`

### If you are changing mesh behavior
Start with:
- `PacketEnvelope.kt`
- relay-handling parts of `LadyaNodeRepository.kt`
- diagnostics fields in `ConnectionUiState`

### If you are changing trust/security behavior
Start with:
- `DeviceIdentityManager.kt`
- profile/trust logic in `LadyaNodeRepository.kt`
- `ProfileScreen.kt`

### If you are changing media behavior
Start with:
- `AudioCallManager.kt`
- `VideoCallManager.kt`
- `CallScreen.kt`

### If you are implementing Stage 5
Start with:
- `security/`
- packet wrapping points in `LadyaNodeRepository.kt`
- profile/connection fingerprint UI
- diagnostics counters in `ConnectionUiState`
