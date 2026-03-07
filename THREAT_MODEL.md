# Threat model (short practical version)

## 1. Scope

This is a practical MVP threat model for Ladya in its current and near-term Stage 5 architecture.
It is intentionally short and focused on the threats that matter for a decentralized local-network messenger.

## 2. Assets to protect

Main assets:
- message contents;
- files, voice notes, and image payloads;
- call media streams;
- node identity and trust state;
- routing integrity;
- local chat history;
- user confidence that they are talking to the expected peer.

## 3. Adversary types

### A. Passive local observer
Capabilities:
- can see local network traffic;
- can observe packet flow;
- may record traffic for later analysis.

### B. Active malicious peer
Capabilities:
- participates in discovery;
- can connect and send crafted packets;
- may attempt spoofing, spam, malformed data, or route abuse.

### C. Malicious relay node
Capabilities:
- forwards traffic;
- observes packet metadata;
- may drop, delay, replay, or tamper with packets.

### D. Identity impostor
Capabilities:
- pretends to be another peer;
- presents changed key material;
- tries to exploit lack of verification.

### E. Local device compromise
Capabilities:
- attacker has access to the phone or app storage.

## 4. Main threats

### Threat 1 — traffic interception
Risk:
A passive observer reads message or file content.

Current mitigation:
- partial identity/authentication groundwork exists.

Required Stage 5 mitigation:
- end-to-end AES-GCM payload encryption;
- per-peer session keys.

### Threat 2 — peer impersonation / key substitution
Risk:
A malicious node claims to be another user.

Current mitigation:
- signed handshake;
- fingerprint display;
- suspicious key-change detection;
- manual verify/block states.

Further mitigation:
- Android Keystore-backed identity keys;
- QR-based fingerprint verification.

### Threat 3 — malicious relay reading payloads
Risk:
Intermediate node reads messages or file contents.

Current mitigation:
- relay sees transport metadata only conceptually, but payload confidentiality is not yet fully enforced.

Required Stage 5 mitigation:
- relay forwards encrypted blobs only.

### Threat 4 — packet tampering
Risk:
Attacker modifies payload in transit.

Mitigation:
- authenticated encryption via AES-GCM;
- integrity checks for file containers/manifests;
- signature-based identity exchange.

### Threat 5 — replay attacks
Risk:
Old packets are resent to confuse state.

Current mitigation:
- packet IDs;
- deduplication;
- TTL handling.

Further mitigation:
- include timestamps and replay checks inside secure container validation.

### Threat 6 — routing abuse / loops / congestion
Risk:
Malicious or broken node causes route loops, flooding, or unnecessary forwarding.

Current mitigation:
- packet IDs;
- TTL;
- relay deduplication;
- reroute logic;
- diagnostics.

Further mitigation:
- stricter rate limiting;
- spam throttling per peer.

### Threat 7 — spam / noisy peers
Risk:
A node sends too many messages or bogus connection attempts.

Current mitigation:
- limited trust controls;
- blocked peer state.

Recommended future mitigation:
- per-peer rate limits;
- soft bans;
- relay admission control.

### Threat 8 — local storage exposure
Risk:
If the device is compromised, chat history may be readable.

Current mitigation:
- local storage exists but is not a hardened secure vault.

Recommended future mitigation:
- minimize sensitive plaintext persistence;
- optionally encrypt selected local records;
- rely on Android Keystore where possible.

### Threat 9 — steganography misuse or misinterpretation
Risk:
Stego payload exceeds capacity, becomes corrupted, or gives a false sense of security.

Mitigation principle:
- stego hides presence, not just content;
- but it must wrap already encrypted secure containers.

## 5. Security guarantees the project should aim for

### What Ladya should protect against
- passive local eavesdropping;
- impersonation without key verification;
- malicious relay reading payload contents;
- accidental route trust misuse;
- file/message tampering in transit;
- packet duplication/loop amplification.

### What Ladya should not overclaim
- full protection against a fully compromised endpoint device;
- guaranteed anonymity against all traffic analysis;
- production-grade resistance to nation-state level traffic correlation;
- perfect metadata secrecy in current MVP form.

## 6. Minimum acceptable Stage 5 outcome

A reasonable minimum security bar for the hackathon version would be:
- Keystore-backed identity keys;
- peer fingerprint verification;
- per-peer session key derivation;
- AES-GCM encrypted text payloads;
- encrypted file containers;
- relay nodes unable to decrypt forwarded payloads;
- visible diagnostics for decryption failures and secure session state.

## 7. Practical summary

The current repository already has good foundations for:
- trust-aware discovery;
- key fingerprint visibility;
- signed handshake;
- suspicious peer detection.

Stage 5 should convert these foundations into real payload confidentiality, so that the system becomes not only decentralized, but also meaningfully secure in mesh operation.
