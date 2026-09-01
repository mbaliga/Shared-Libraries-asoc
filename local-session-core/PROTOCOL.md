# Local session protocol v1

## Product boundary

This protocol coordinates people and devices that have explicitly joined one short-lived local
session. It is not a social graph, account system, cloud lobby, long-term athlete database, chat
archive or inference engine. Apps persist selected evidence through their own consent and
provenance systems.

## Authority and ordering

1. One host creates the session and assigns the canonical revision/event sequence.
2. Every command carries the session ID, message ID and actor ID.
3. A message ID is applied at most once within the bounded retry window.
4. Only the host changes the lifecycle phase.
5. A participant may change only their own ready/heartbeat state.
6. App events are opaque to this module and remain explicitly attributed to their sender.
7. Late joiners receive a snapshot plus only the bounded recent-event window. Durable history is
   an app-owned evidence stream, not unbounded session memory.

## Roles

- `HOST`: the single authority for phase transitions and room closure.
- `COACH`: a domain-facing participant; no implicit administrative power.
- `PLAYER`: athlete, musician, learner or other active participant.
- `OBSERVER`: a non-playing participant or additional capture device.

A participant may have multiple non-host roles. Only the participant created with the session has
`HOST`.

## Transport adapter requirements

A production nearby-device adapter must:

- authenticate every command to the actor it names;
- display a human-verifiable pairing code or equivalent authenticated ceremony;
- encrypt traffic in transit;
- reject replay outside the engine's bounded message window;
- cap connection attempts and bytes before decoding;
- never require a vendor cloud for same-device/offline use;
- expose disconnect/reconnect and host-loss states to the app;
- preserve command order or retry until the host returns a canonical snapshot;
- test airplane mode, rotation/backgrounding and network changes on physical devices.

WebRTC, Nearby Connections, LAN sockets and same-process calls are transport choices, not protocol
semantics. This separation keeps Crocodyl/Bocal event models stable if a transport is replaced.

## Acceptance thresholds

- Duplicate command: zero repeated state transitions or app events.
- Canonical sequence: strictly increasing by one for every accepted app event.
- Snapshot convergence: all connected clients reach the host revision within 500 ms on a healthy
  local link; this is a transport/device acceptance target, not claimed by core unit tests.
- Rejoin: a reconnecting participant receives current phase, roster and recent events without
  changing their stable participant ID.
- Memory: recent events and processed message IDs never exceed configured bounds.
- Privacy: no telemetry, storage or network permission is introduced by this module.
