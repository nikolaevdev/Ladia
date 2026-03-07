# Stage 5 security plan

## 1. Goal of Stage 5

Stage 5 introduces a real security layer on top of the already working direct and mesh communication stack.
The key architectural rule is simple:

**security must be added above the existing transport, not by breaking the current mesh implementation.**

This means:
- the current relay/routing pipeline stays intact;
- `PacketEnvelope` remains the carrier;
- mesh nodes continue to forward packets;
- the forwarded payload becomes an encrypted blob that intermediates cannot read.

## 2. Current baseline before Stage 5

Already present in the repository:
- local device identity;
- public key fingerprinting;
- signed handshake payloads;
- short authentication string;
- trust statuses and trust-aware routing;
- a stego image capsule mechanism for trust metadata.

What is still missing for full Stage 5:
- secure key storage in Android Keystore;
- per-peer session key agreement;
- message payload encryption;
- secure file/voice/image container format;
- steganographic secure transport;
- encrypted-payload diagnostics counters.

## 3. Design constraints

Stage 5 must not break:
- `LadyaNodeRepository`
- `PacketEnvelope`
- relay pipeline
- current mesh routing
- current group delivery semantics

Therefore Stage 5 should be implemented as **new security modules** plus **integration points** in the repository.

## 4. Recommended Stage 5 module set

### `security/IdentityKeyManager.kt`
Responsibilities:
- generate identity key pair;
- store private key in Android Keystore;
- export public key bytes/base64;
- derive fingerprint;
- expose stable key alias.

### `security/SessionKeyManager.kt`
Responsibilities:
- derive shared secret with remote public key;
- apply HKDF;
- cache per-peer session keys;
- rotate or refresh if needed.

### `security/SecurePayloadCipher.kt`
Responsibilities:
- AES-GCM encryption/decryption;
- nonce generation;
- auth tag handling;
- authenticated data binding if needed.

### `security/SecureContainer.kt`
Responsibilities:
- strongly typed secure payload model;
- sender peer id;
- timestamp;
- payload type;
- metadata;
- encrypted payload bytes.

### `security/SecureContainerCodec.kt`
Responsibilities:
- encode/decode secure container binary format;
- validate magic/version;
- parse metadata.

### `security/StegoTransportManager.kt`
Responsibilities:
- embed secure payload into supported cover media;
- extract payload from image/audio;
- size/capacity validation;
- fail fast on insufficient carrier capacity.

### `security/FingerprintQrCodec.kt`
Responsibilities:
- encode identity fingerprint or public key summary to QR payload;
- decode verification QR;
- provide profile screen integration helpers.

## 5. Stage-by-stage implementation plan

## Stage 5.1 — Identity keys

### Objective
Replace or extend the current identity handling with Android Keystore-backed identity keys.

### Required output
Implement `IdentityKeyManager` that:
- creates an identity keypair on first use;
- stores the private key in Android Keystore;
- exposes the public key;
- exposes fingerprint;
- does not require mesh changes.

### Integration guidance
- keep `DeviceIdentityManager` working during migration;
- introduce adapter methods so the rest of the app can gradually switch;
- profile and connection screens should continue to show fingerprint;
- handshake can temporarily remain signature-based if needed, but key origin should move to Keystore.

### Safe integration strategy
1. Create `IdentityKeyManager`.
2. Add non-breaking adapter methods.
3. Use it first for local profile/fingerprint display.
4. Later connect it to secure session derivation.

## Stage 5.2 — Session keys

### Objective
Create a per-peer secret using local private identity key and remote public key.

### Target scheme
- key agreement: `X25519`
- KDF: `HKDF`

### Expected flow
`sharedSecret = X25519(localPrivateKey, remotePublicKey)`  
`sessionKey = HKDF(sharedSecret)`

### Constraints
- session keys must be peer-scoped;
- relay nodes must never need them;
- no routing changes;
- cache carefully to avoid repeated expensive derivations.

## Stage 5.3 — Secure messages

### Objective
Encrypt text payloads before sending.

### Target scheme
- `AES-GCM-256`

### Before send
`plaintext -> encrypt -> nonce + ciphertext + authTag`

### On receive
`nonce + ciphertext + authTag -> decrypt -> plaintext`

### Important note
Only the payload should be encrypted.
The envelope remains visible for transport/routing.

### Expected envelope integration
The existing envelope may include metadata such as:
- `payloadEncryption = E2EE`
- `payloadType = secure`

The actual secure container goes inside `payload`.

## Stage 5.4 — Secure file containers

### Objective
Transmit files as encrypted containers rather than plain transfer payloads.

### Proposed container format
`LADYA_SECURE_FILE`

Suggested fields:
- magic
- version
- senderPeerId
- timestamp
- originalName / mimeType metadata
- nonce
- ciphertext
- authTag

### Important principle
The receiver should only reconstruct the original file after successful decryption and authentication.

## Stage 5.5 — Secure typed containers

### Supported payload types
- `secure_message`
- `secure_file`
- `secure_voice`
- `secure_image`

### Unified container idea
Each secure container should contain:
- sender peer id
- timestamp
- payload type
- encrypted payload
- optional metadata

This unifies direct delivery, relay delivery, persistence, and future stego wrapping.

## Stage 5.6 — Steganographic transport

### Objective
Allow selected secure payloads to travel as apparently ordinary media.

### Proposed methods
#### Images
- LSB steganography

#### Audio
- phase encoding

### Practical MVP advice
For the first working step:
- start with image LSB only;
- support only small secure payloads;
- add audio stego later if time remains.

### Important design note
Stego must wrap the **secure container**, not raw plaintext.
Otherwise stego hides traffic shape but not content confidentiality.

## Stage 5.7 — Mesh compatibility

### Objective
Ensure relay nodes only forward opaque encrypted blobs.

### Relay visibility should be limited to
- outer packet envelope;
- sender/target packet metadata;
- TTL / ack data;
- maybe secure-payload type marker.

### Relay nodes must not see
- plaintext message text;
- decrypted file contents;
- decrypted voice/image payloads.

## 6. Diagnostics additions

Add the following counters to connection/security diagnostics:
- `encryptedPacketsSent`
- `encryptedPacketsReceived`
- `decryptionErrors`
- `stegoPacketsDetected`

Optional useful counters:
- `secureContainersCreated`
- `secureContainersOpened`
- `sessionKeysActive`
- `identityVerificationFailures`

## 7. UI additions

### Profile screen
Add:
- identity fingerprint;
- full public-key-derived fingerprint view;
- verify identity action;
- QR code export/import for verification.

### Connection screen
Add:
- indication that peer session is encrypted or not;
- key verification state;
- maybe active secure-session indicator.

### Chat screen
Possible later additions:
- secure message badge;
- stego message badge;
- decryption error system bubble.

## 8. Persistence considerations

Decide explicitly what is stored locally:
- decrypted plaintext only after successful open;
- secure blob plus metadata;
- or both.

Recommended MVP approach:
- store the human-readable message after local decrypt for UI continuity;
- store enough metadata to mark that the message was originally secured;
- do not store long-lived session keys in SQLite.

## 9. Suggested implementation order

1. `IdentityKeyManager`
2. adapter layer to current identity flow
3. `SessionKeyManager`
4. `SecurePayloadCipher`
5. secure message send/receive
6. secure container codec
7. secure file container
8. diagnostics counters
9. profile QR verification
10. image stego wrapping
11. audio stego wrapping if time allows

## 10. Risks

### Risk 1 — breaking current handshake
Mitigation:
- keep compatibility path until new identity handling is proven.

### Risk 2 — breaking relay compatibility
Mitigation:
- do not modify routing logic;
- only wrap payload contents.

### Risk 3 — oversized stego payloads
Mitigation:
- enforce maximum payload size;
- show explicit UI error when the selected carrier cannot hold the data.

### Risk 4 — mixing current identity model with new one
Mitigation:
- add transitional adapter methods;
- mark old identity store as legacy if Keystore becomes primary.

## 11. Recommended first deliverable

The first safe deliverable is exactly what your current Stage 5 plan asks for:

### Deliverable
`IdentityKeyManager`

### It should:
- generate an identity key pair;
- persist the private key in Android Keystore;
- return public key bytes/base64;
- return fingerprint;
- be usable from profile and connection UI without changing mesh routing.

That gives a clean base for all later secure-session work.
