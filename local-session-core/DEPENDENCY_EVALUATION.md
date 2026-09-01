# Dependency evaluation — 2026-09-01

The implementation intentionally reuses platform/network libraries at the transport boundary and
does not copy or fork third-party networking code into this module.

| Candidate | License | Useful part | Why it is not the session core |
|---|---|---|---|
| [Trystero](https://github.com/dmotz/trystero) | MIT | Excellent browser WebRTC rooms, actions, chunking and encrypted peer data | Browser/JavaScript transport. Peer discovery still uses Nostr, MQTT, BitTorrent, IPFS, Firebase, Supabase or a relay; that is not Crocodyl's fully offline same-device guarantee or a Kotlin domain state machine. A future Bocal web adapter should prefer it where internet-assisted discovery is acceptable. |
| [PeerJS](https://github.com/peers/peerjs) and [PeerServer](https://github.com/peers/peerjs-server) | MIT | Mature browser WebRTC data/media channels | Requires signaling through PeerServer and provides no coach/athlete authority, lifecycle, role, event-attribution or bounded-history semantics. Suitable adapter substrate, not the shared product contract. |
| [NetplayJS](https://github.com/rameshvarun/netplayjs) | ISC | Browser game rollback/lockstep and matchmaking | Its fixed-timestep game simulation is the wrong consistency model for scores, coaching cues, captures and evidence records. |
| [Nearby Connections](https://developers.google.com/nearby/connections/overview) | Google Play services; documentation/sample terms differ from an Apache/MIT library | Strong offline Android/iOS discovery and payload transport over Bluetooth/Wi-Fi | Not a permissively licensed, cross-platform session core and may introduce Google service/analytics policy. It can be an explicit Android adapter only after the product accepts those dependencies and physical-device pairing tests pass. |

## Decision

Build only the missing application-semantic layer here: deterministic authority, participants,
roles, lifecycle, ordered attributed events, retries and bounded state. Do not reimplement WebRTC,
Bluetooth, Wi-Fi discovery, cryptography or databases. Consumers select a transport adapter and
must meet the security requirements in `PROTOCOL.md`.

No third-party source code is included in `local-session-core` 0.1.0, so this module adds no
third-party notice beyond the repository's Apache-2.0 license.
