# Stage history

This document summarizes the implementation evolution visible in the repository and in the stage-specific markdown files.

## Stage 1 — initial Android P2P MVP

Main result:
- first decentralized Android messenger foundation without central server.

Core additions:
- local node bootstrapping;
- peer discovery in local network;
- direct connection establishment;
- text chat;
- contact/thread basics;
- local profile foundation.

## Stage 2 — protocol and profile strengthening

Main result:
- repository became more structured around device identity and profile exchange.

Core additions and revisions across Stage 2 sub-iterations:
- improved direct session behavior;
- keypair-related groundwork;
- profile propagation;
- SQL-based chat persistence direction;
- gradual UX and protocol corrections.

## Stage 3 — media and richer chat features

### Stage 3.1 — audio
- voice-related functionality introduced;
- audio communication foundation expanded.

### Stage 3.2 — speaker toggle / sounds
- speaker behavior improved;
- app feedback sounds introduced;
- UX around audio state became clearer.

### Stage 3.3 — call UX
- better call-screen behavior;
- improved user handling of active/incoming call states.

### Stage 3.3.2 — call popup notifications
- incoming call interaction moved closer to usable mobile UX.

### Stage 3.4 — video MVP
- 1:1 video communication added.

### Stage 3.4.x fixes
- video UI and call stability refinements.

### Stage 3 summary
By the end of Stage 3, the project already supported:
- text chat;
- file exchange;
- voice notes;
- audio calls;
- video call MVP;
- better notifications and media UX.

## Stage 4 — mesh, relay, recovery, groups

Stage 4 is the most important networking expansion of the repository.
It transforms a direct P2P messenger into a **mesh-capable decentralized communication system**.

### Stage 4.1 — mesh recovery foundation
Main additions:
- self-healing recovery after active peer loss;
- preservation of listening/discovery mode;
- reconnect attempts;
- fallback to reserve neighbor.

Why it mattered:
The node no longer “dies” logically when one direct peer disappears.

### Stage 4.2 — mesh text relay
Main additions:
- dedicated relay listener on port 1907;
- relay packet types;
- route labels in UI;
- relay packet deduplication;
- pending relay queue;
- retry after route discovery.

Why it mattered:
Text traffic could now pass through another peer.

### Stage 4.3 — mesh file relay + diagnostics
Main additions:
- file relay support via manifest/control/chunk/complete flow;
- diagnostics/topology visibility.

Why it mattered:
The project moved beyond relay text and started handling richer content through the mesh layer.

### Stage 4.4 — trust-aware relay
Main additions:
- blocked routes excluded;
- verified routes preferred;
- warnings for unverified/suspicious relay paths;
- diagnostics counters for route trust composition.

Why it mattered:
Routing became security-aware rather than purely connectivity-based.

### Stage 4.5 — multi-neighbor reroute
Main additions:
- best relay candidate selection among multiple neighbors;
- penalty for recently failed relay nodes;
- reroute and recovered-route counters;
- alternative relay retry.

Why it mattered:
The mesh layer became more fault-tolerant.

### Stage 4.6 — group foundation + voice
Main additions:
- early group communication basis;
- voice-related groundwork for group interactions.

### Stage 4.7 — group chat over mesh
Main additions:
- group creation;
- invitations;
- group text messages;
- separate `group:<id>` thread model;
- group headers with membership;
- direct/relay group delivery depending on route availability.

Why it mattered:
The system evolved from pairwise communication toward multi-party communication.

### Stage 4.8 — group file/voice/diagnostics
Main additions:
- MVP group file delivery through mesh;
- group voice delivery;
- group diagnostics counters;
- fixes around group model definitions.

### Stage 4.8.1 — group audio / create UX
- UX refinement for group creation and audio-related flows.

### Stage 4.8.2 — separate group window
- dedicated group chat route/screen;
- audio/video calls disabled in group mode;
- cleaner routing and UX separation.

### Stage 4.8.3 — group settings fix
- settings related stability improvements.

### Stage 4.8.4 — relay patch
- compile-time defect fix for relay forwarding helper use.

### Stage 4.8.5 — group fixes / settings / save-on-demand
Main additions:
- fixed group leave flow;
- stronger group settings opening logic;
- explicit owner/admin rights checks;
- group attachments continue to arrive without confirm-flow;
- saving a copy of group attachment/voice note on user request from a message bubble.

### Stage 4 summary
By the end of Stage 4.8.5, the project already had:
- direct communication;
- relay communication;
- multihop-oriented logic foundation;
- self-healing network behavior;
- trust-aware routing;
- file and media features;
- group chat and group content delivery;
- diagnostics and topology counters.

## Stage 5 — security layer in progress

Stage 5 is the next logical step and should add **payload confidentiality and stronger identity binding** on top of the already working mesh network.

Target scope:
- Android Keystore identity keys;
- per-peer session key derivation;
- AES-GCM secure message encryption;
- secure containers for files/voice/images;
- steganographic transport;
- mesh-compatible encrypted blobs;
- fingerprint verification UX via QR.

## Important continuity rule

The project history shows a clear constraint:
**new stages must extend, not break, previous stages**.

For Stage 5 this means:
- do not rewrite mesh transport from scratch;
- do not break `PacketEnvelope`;
- do not replace routing with incompatible logic;
- add security as a new layer above the existing direct/relay pipeline.
