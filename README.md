# Ladya

//// 
после 21:00
Демо видео https://disk.yandex.ru/i/Zz_fWZmgak-iZg.
Ru перевод https://github.com/nikolaevdev/Ladia/tree/main/ru_documentation
////

**Ladya** is an Android prototype of a decentralized peer-to-peer messenger without a central server.
The application is designed for local autonomous communication between devices inside one Wi-Fi network, with support for direct sessions, relay delivery, file exchange, voice communication, and 1:1 video calls.

Current repository snapshot corresponds to **Stage 4.8.5**.  
**Stage 5** is in progress and is dedicated to security: end-to-end encryption, secure containers, and steganographic transport.

## Project goal

The project is being developed as a decentralized communication system for environments where centralized infrastructure is unavailable, unstable, overloaded, or undesirable. The target scenarios are aligned with the hackathon assignment: node discovery, P2P session establishment, text messaging, multihop relay, file transfer, real-time communication, basic node authentication, encryption, diagnostics, and documented architecture. These requirements are explicitly listed in the technical assignment for the Hex.Team hackathon. fileciteturn0file0

## Current implementation status

### Already implemented

#### Stage 1–2 foundation
- local node profile;
- node publication and discovery inside the local network;
- direct device-to-device session establishment;
- chat thread list and contacts;
- text messages;
- SQLite-based local persistence;
- profile synchronization basics.

#### Stage 3 communication layer
- 1:1 P2P text chat;
- file transfer with manifests/chunks/statuses;
- voice notes;
- 1:1 audio call over UDP;
- 1:1 video call MVP over UDP;
- adaptive bitrate / lightweight video profile adaptation;
- background service and notifications.

#### Stage 4 mesh and group layer
- self-healing recovery after node loss;
- relay delivery for text messages;
- relay delivery for files;
- trust-aware route selection;
- multi-neighbor rerouting;
- mesh diagnostics and topology counters;
- group chat foundation;
- group invitations and membership handling;
- group text messaging through mesh;
- group file relay;
- group voice delivery;
- separate group screen;
- group settings, role checks, owner/admin logic;
- attachment save-on-demand from message bubbles.

### Already present as security groundwork
Although Stage 5 is not finished yet, the repository already contains the first security-oriented building blocks:
- device identity generation and local persistence;
- public key fingerprint calculation;
- signed HELLO / HELLO_ACK / HELLO_CONFIRM handshake;
- short authentication string for manual identity comparison;
- trust statuses: `Unverified`, `Verified`, `Suspicious`, `Blocked`;
- steganographic trust capsule for embedding signed trust metadata into PNG images.

## What Stage 5 will add

Stage 5 extends the existing transport without breaking the mesh core:
- identity keys stored in Android Keystore;
- session keys derived per peer using X25519 + HKDF;
- end-to-end encryption of messages via AES-GCM;
- encrypted secure containers for files, voice, and images;
- steganographic transport for hidden payload delivery;
- new diagnostics counters for encrypted and stego traffic;
- identity verification UX via fingerprint and QR.

## Actual transport model in this repository

This repository snapshot currently works over **local Wi-Fi / LAN discovery**, not over Internet and not through a central backend.
The implemented network stack is the following:

- **NSD (Network Service Discovery)** for peer publication and discovery inside the same network;
- **TCP socket** for the primary direct messaging session;
- **TCP relay socket** for mesh forwarding;
- **TCP file-transfer socket** for file payload delivery;
- **UDP audio transport** for voice call packets;
- **UDP video transport** for lightweight 1:1 video streaming.

In other words, the application is already decentralized, but the current codebase should be understood as a **local-network P2P / mesh prototype**. If the project later evolves toward Wi-Fi Direct or Bluetooth-based discovery, that will be an architectural extension, not the current runtime model.

## Ports used by the current implementation

- `1903` — primary TCP session
- `1904` — file transfer listener
- `1905` — audio UDP
- `1906` — video UDP
- `1907` — relay listener

## High-level architecture

The application is organized around one central orchestration layer:

- `LadyaNodeRepository` — the main coordinator of network state, session state, UI state, persistence, routing, relay, profile sync, file transfer, group logic, and call coordination.

Supporting modules:
- `network/protocol/PacketEnvelope.kt` — transport envelope;
- `network/protocol/PacketFactory.kt` and `PacketParser.kt` — packet creation/parsing;
- `network/protocol/TransferManifest.kt` — file transfer manifest model;
- `call/AudioCallManager.kt` — UDP audio pipeline;
- `call/VideoCallManager.kt` — UDP video pipeline;
- `security/DeviceIdentityManager.kt` — local identity, fingerprints, signatures, SAS;
- `security/StegoTrustCapsuleManager.kt` — LSB-based hidden trust capsules in images;
- `LadyaDatabaseHelper.kt` — SQLite persistence;
- `ui/` — Compose screens, components, and UI models;
- `navigation/` — application routes and host.

A more detailed breakdown is available in [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Application screens

The current navigation contains the following routes:
- `Chats`
- `Connection`
- `Chat`
- `GroupChat`
- `Call`
- `Logs`
- `AddContact`
- `Settings`
- `Profile`

### Main user flows

1. Start the app.
2. The foreground service and local repository are initialized.
3. The node publishes itself in the local network and starts discovery.
4. The user sees discovered peers and can connect.
5. After session establishment, the user can:
   - send text messages;
   - send files;
   - record and send voice notes;
   - start a 1:1 audio call;
   - start a 1:1 video call;
   - create a group;
   - manage trust and view profile data.
6. If the direct target is not reachable, mesh relay and rerouting logic may deliver traffic through intermediate peers.

## Security model in the current snapshot

The current snapshot should be treated as **authenticated transport with trust controls**, but **not yet full end-to-end encrypted messaging**.

Implemented now:
- per-device cryptographic identity;
- signed node handshake;
- fingerprint comparison;
- suspicious key-change detection;
- verified and blocked peer states;
- trust-aware route selection.

Not yet completed in the current snapshot:
- full E2EE payload encryption for all message types;
- per-peer session key derivation via X25519;
- encrypted file/voice/image secure containers as the default transport;
- mesh-level encrypted payload wrapping for all relayed data.

## Repository layout

```text
app/
  src/main/java/ru/mishanikolaev/ladya/
    call/
    navigation/
    network/
      protocol/
      transport/
    notify/
    security/
    service/
    sound/
    ui/
      components/
      models/
      screens/
      theme/
    MainActivity.kt
```

A more explicit module map is available in [`REPOSITORY_STRUCTURE.md`](REPOSITORY_STRUCTURE.md).

## Build requirements

- Android Studio with Kotlin/Compose support
- Android SDK 35
- Min SDK 26
- Java 11 target
- Android device or emulator for UI testing
- preferably two or more physical devices in the same local Wi-Fi network for network tests

## Main dependencies

- Kotlin `2.0.21`
- Android Gradle Plugin `8.7.3`
- Compose BOM `2024.09.00`
- Material 3
- AndroidX Lifecycle Runtime KTX `2.8.7`
- Activity Compose `1.9.3`
- CameraX `1.4.1`

## How to run

1. Clone the repository.
2. Open it in Android Studio.
3. Make sure the Android SDK is installed.
4. Open the `app` module and sync Gradle.
5. Run the app on at least two devices connected to the same network.
6. Allow microphone, camera, and notifications where needed.

## Suggested test scenarios

### Basic connectivity
- start the app on two devices;
- confirm both nodes appear in discovery;
- establish a direct session;
- verify text messages in both directions.

### Relay / mesh
- keep one device as a relay;
- route text or file traffic through it;
- verify diagnostics counters grow;
- simulate route loss and confirm reroute recovery.

### File delivery
- send a small file;
- confirm manifest, progress, and completion;
- confirm the receiver can save/open the file.

### Audio / video
- establish a call;
- verify packet counters and duration;
- test microphone mute / speaker mode / camera toggle.

### Group mode
- create a group;
- send group text;
- test owner/admin role checks;
- remove a member;
- rename the group.

## Known limitations of the current snapshot

- the central repository is very large and combines many responsibilities;
- the mesh implementation is an MVP and not yet a full distributed routing layer;
- Stage 5 E2EE is still in progress;
- secure containers are not yet the default for all message/file types;
- current steganography support is limited to trust capsule embedding in images;
- the build was not revalidated inside this environment because the Gradle wrapper could not download the distribution without external network access.

## Documentation included in this package

- [`ARCHITECTURE.md`](ARCHITECTURE.md)
- [`REPOSITORY_STRUCTURE.md`](REPOSITORY_STRUCTURE.md)
- [`STAGE_HISTORY.md`](STAGE_HISTORY.md)
- [`STAGE_5_SECURITY_PLAN.md`](STAGE_5_SECURITY_PLAN.md)
- [`THREAT_MODEL.md`](THREAT_MODEL.md)

## Roadmap

### Near-term
- Stage 5.1: Android Keystore identity keys
- Stage 5.2: per-peer session keys via X25519 + HKDF
- Stage 5.3: AES-GCM encryption for message payloads
- Stage 5.4: secure file containers
- Stage 5.5: secure voice/image containers
- Stage 5.6: steganographic transport for secure payloads
- Stage 5.7: mesh-compatible encrypted blob relay

### Later
- repository modularization;
- transport abstraction split;
- better automated tests;
- architecture diagram assets for defense/demo;
- Wi-Fi Direct / alternative discovery experiments if needed.

## License

At the moment, no explicit license file is included in this package. Add one before publishing publicly.
