# Architecture of Ladya

## 1. Architectural overview

Ladya is an Android decentralized messenger built around a **single-process local node** running on each device.
Each running application instance behaves as a local communication node that can:
- publish itself in the network;
- discover other nodes;
- establish direct sessions;
- relay packets for other peers;
- transfer files;
- participate in group delivery;
- place audio/video calls;
- maintain local identity and trust data;
- persist chat state in SQLite.

From an architectural perspective, the current implementation is a **monolithic-but-layered Android MVP**, where one large repository coordinates the entire communication lifecycle.

## 2. Main architectural layers

### UI layer
Located in:
- `ui/screens/`
- `ui/components/`
- `ui/models/`
- `navigation/`

Responsibilities:
- Compose rendering;
- user input;
- displaying connection/chat/call/log/profile/settings state;
- dispatching actions to the repository.

Important detail:
The UI follows a state-driven approach. Most screens consume immutable UI state models such as:
- `ConnectionUiState`
- `ChatUiState`
- `CallUiState`
- `ContactsUiState`
- `SettingsUiState`
- `ProfileUiState`

### Application/service layer
Located in:
- `MainActivity.kt`
- `service/LadyaForegroundService.kt`
- `notify/AppNotificationManager.kt`

Responsibilities:
- app bootstrap;
- foreground/background awareness;
- notifications;
- keeping the node alive during long-running communication tasks.

### Core coordination layer
Located in:
- `network/LadyaNodeRepository.kt`

This is the main orchestrator.
It currently combines:
- discovery;
- direct connection management;
- relay routing;
- file transport;
- group logic;
- profile/trust sync;
- persistence sync;
- call state integration;
- diagnostics and logs;
- navigation events.

### Protocol layer
Located in:
- `network/protocol/PacketEnvelope.kt`
- `network/protocol/PacketFactory.kt`
- `network/protocol/PacketParser.kt`
- `network/protocol/TransferManifest.kt`
- `network/protocol/ProtocolConstants.kt`

Responsibilities:
- transport envelope structure;
- packet serialization/parsing;
- manifest schema for file transfer;
- protocol constants and type naming.

### Media transport layer
Located in:
- `call/AudioCallManager.kt`
- `call/VideoCallManager.kt`

Responsibilities:
- UDP sockets;
- capture / send / receive loops;
- audio and video packet counting;
- mute/speaker/video state changes;
- adaptive streaming behavior.

### Security / trust groundwork layer
Located in:
- `security/DeviceIdentityManager.kt`
- `security/StegoTrustCapsuleManager.kt`

Responsibilities:
- device identity generation;
- fingerprint derivation;
- signature generation and verification;
- short auth string derivation;
- hidden trust-capsule image embedding/extraction.

### Persistence layer
Located in:
- `network/LadyaDatabaseHelper.kt`

Responsibilities:
- SQLite schema management;
- messages;
- threads;
- contacts;
- saved profile/trust-related data;
- session history and local state restoration.

## 3. Runtime architecture

At runtime, one device hosts several logical channels:

### 3.1 Discovery channel
Mechanism:
- NSD service registration;
- NSD browsing in the same Wi-Fi network.

Purpose:
- find peers;
- expose local port and basic metadata;
- keep discovered peer list fresh.

### 3.2 Main direct session channel
Transport:
- TCP socket on port `1903`.

Purpose:
- direct peer-to-peer session;
- control messages;
- text payloads;
- handshake and profile exchange;
- session continuity for the active chat.

### 3.3 File channel
Transport:
- TCP socket on port `1904`.

Purpose:
- file offer/accept flow;
- transfer manifest and chunk-based file delivery;
- progress tracking and completion.

### 3.4 Audio call channel
Transport:
- UDP on port `1905`.

Purpose:
- near real-time audio exchange.

### 3.5 Video call channel
Transport:
- UDP on port `1906`.

Purpose:
- lightweight real-time video streaming.

### 3.6 Relay channel
Transport:
- TCP socket on port `1907`.

Purpose:
- mesh forwarding;
- relay packet processing;
- multihop-compatible text/group/file forwarding.

## 4. Central repository responsibilities

`LadyaNodeRepository` is currently the most important class in the system.

It is responsible for:
- node initialization;
- screen state exposure;
- discovery lifecycle;
- socket lifecycle;
- handshake processing;
- discovered peer map;
- saved contacts;
- thread list building;
- chat message insertion/update/delete;
- file send/receive flow;
- relay queue and forwarding;
- reroute and recovery logic;
- group state and membership mutations;
- profile and trust operations;
- diagnostics counters;
- navigation events;
- coordination with audio/video managers.

### Architectural consequence
This file is powerful but very large.
For documentation and future refactoring, it is best understood as a combination of the following logical subsystems:
- `NodeLifecycleController`
- `DiscoveryController`
- `DirectSessionController`
- `RelayController`
- `FileTransferController`
- `GroupController`
- `TrustController`
- `ChatPersistenceController`
- `CallCoordinator`
- `NavigationMediator`

These are logical roles only; they are not yet separate classes.

## 5. Packet model

### 5.1 Envelope
The transport envelope is represented by `PacketEnvelope`.
It contains:
- `packetId`
- `type`
- `protocol`
- `version`
- `timestamp`
- `senderPeerId`
- `targetPeerId`
- `ackId`
- `ttl`
- `payload`

This envelope is the stable carrier for direct and relay traffic.
Stage 5 must preserve compatibility with it.

### 5.2 Why the envelope matters
The current mesh/relay pipeline depends on the envelope shape for:
- deduplication;
- TTL-based loop protection;
- sender/target addressing;
- acknowledgements;
- payload dispatch by packet type.

Because of this, Stage 5 must **wrap encrypted payloads inside the existing envelope**, not replace the relay protocol.

## 6. Handshake and trust model

The current trust model is stronger than a plain anonymous socket connection.

### Current handshake flow
The repository uses signed identity exchange based on:
- `HELLO`
- `HELLO_ACK`
- `HELLO_CONFIRM`

The exchange uses:
- local public key;
- nonces;
- signatures;
- fingerprint derivation;
- short auth string calculation.

### Current peer trust states
- `Unverified`
- `Verified`
- `Suspicious`
- `Blocked`

### Trust-aware behavior already implemented
- blocked routes are excluded from relay selection;
- verified routes are preferred;
- suspicious peers produce warnings;
- profile screens allow verify/block/unblock actions.

## 7. Message architecture

### Direct message path
1. User writes a message in `ChatScreen`.
2. UI dispatches `ChatAction.SendMessageClicked`.
3. Repository builds a packet.
4. Packet is sent through the active direct session.
5. Local UI state and SQLite are updated.
6. Delivery status is reflected in the message bubble.

### Relay message path
1. Direct target is unavailable or another peer is selected.
2. Repository resolves a relay-capable route.
3. Packet is wrapped as a relay packet.
4. Intermediate peer forwards it.
5. Receiver processes payload and sends ACK.
6. Diagnostics counters are updated.

## 8. File transfer architecture

The file transport is protocol-driven.

Main concepts:
- transfer manifest;
- chunking;
- checksum;
- transfer progress;
- separate file listener;
- incoming file offers;
- receiver-side acceptance.

The relay-enabled stages extended this logic to support indirect file delivery through the mesh layer.

## 9. Call architecture

### Audio
`AudioCallManager` handles:
- microphone capture;
- UDP send loop;
- UDP receive loop;
- packet counters;
- playback state;
- mute/speaker management.

### Video
`VideoCallManager` handles:
- camera frames;
- UDP packetization;
- remote frame reconstruction/display;
- bitrate/profile adaptation;
- local camera switching;
- mute/video-off states.

### Important scope note
Calls are implemented for **1:1 mode**.
Group call functionality is intentionally not the current scope.

## 10. Group architecture

Current group mode is an MVP built on top of the existing message transport.

Supported:
- group creation;
- invitations;
- group title;
- members;
- owner/admin flags;
- group message thread key (`group:<id>`);
- group file and group voice delivery;
- group settings UI;
- member removal.

Important limitation:
Membership is not yet a fully distributed consensus-based group protocol. It is an application-level mesh group implementation suitable for MVP demonstration.

## 11. Persistence architecture

SQLite is used for local persistence.
The local store is responsible for:
- chat threads;
- messages;
- contacts;
- profile fragments;
- identity/trust-related UI continuity;
- restoring conversation history across restarts.

This gives the app offline continuity even when no network session is active.

## 12. Diagnostics architecture

Diagnostics are visible primarily through connection/log screens and related counters.

Current counters include areas such as:
- relay forwarded packets;
- relay dropped packets;
- trusted/untrusted/blocked routes;
- rerouted packets;
- recovered routes;
- group packets sent/received/dropped;
- audio/video packet counters;
- bytes sent/received.

These counters are important both for debugging and for hackathon demo evidence.

## 13. Stage 5 architectural integration strategy

Stage 5 must not break:
- `LadyaNodeRepository`
- `PacketEnvelope`
- relay pipeline;
- route resolution;
- existing mesh semantics.

Therefore the correct integration strategy is:

1. Keep the current packet envelope.
2. Encrypt only the payload body.
3. Mark secure payload type in metadata.
4. Let relay nodes forward encrypted blobs without decryption.
5. Keep diagnostics and routing visible at envelope level.

### Recommended new Stage 5 modules
- `security/IdentityKeyManager.kt`
- `security/SessionKeyManager.kt`
- `security/SecurePayloadCipher.kt`
- `security/SecureContainer.kt`
- `security/SecureContainerCodec.kt`
- `security/StegoTransportManager.kt`
- `security/FingerprintQrCodec.kt`

## 14. Main architectural strengths

- clear local-first design;
- no central backend dependency;
- direct + relay communication model;
- real-time communication already integrated;
- trust-aware routing is already present;
- packet envelope already suitable for secure wrapping;
- good fit for demo scenarios from the hackathon specification. fileciteturn0file0

## 15. Main architectural weaknesses

- repository is too large and highly coupled;
- protocol types are partially string-driven;
- test coverage is minimal;
- some behaviors are MVP-grade rather than production-hardened;
- Stage 5 secure transport is not yet fully integrated.

## 16. Recommended next refactoring direction

After Stage 5 is stabilized, the most beneficial refactoring would be:
- split repository into focused managers/controllers;
- separate direct transport from mesh transport;
- introduce explicit packet type registry;
- isolate persistence from network orchestration;
- add integration tests for handshake, relay, reroute, and secure payload wrapping.
