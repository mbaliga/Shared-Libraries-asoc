# Local session core

`dev.aarso:local-session-core:0.1.0` is the small shared state machine behind local
multi-participant features. It is intended for Bocal ensembles, Crocodyl athlete/coach sessions,
multi-camera capture, Ebbflow guided sessions and later squad workflows.

The core deliberately does **not** discover devices, open sockets, persist personal data, render
UI or choose an app's domain events. A host app owns those policies. The module provides:

- a deterministic, host-authoritative room state machine;
- host, coach, player and observer roles;
- lobby, active, paused and closed phases;
- ordered app-defined events with optional recipients;
- idempotent command handling for retrying transports;
- bounded recent-event and message-id windows for long sessions;
- immutable snapshots and transition notifications;
- an in-process coordinator for same-device use and transport adapters.

## Minimal use

```kotlin
val engine = LocalSessionEngine.create(
    sessionId = SessionId("practice-2026-09-01"),
    hostId = ParticipantId("coach-device"),
    hostDisplayName = "Coach",
)
val coordinator = LocalSessionCoordinator(engine)

coordinator.dispatch(
    JoinSession(
        sessionId = engine.snapshot().sessionId,
        messageId = MessageId("join-archer-1"),
        participant = ParticipantCandidate(
            id = ParticipantId("archer-1"),
            displayName = "Archer",
            roles = setOf(ParticipantRole.PLAYER),
        ),
    ),
)
```

An Android Nearby, LAN, WebRTC or QR/manual-signalling adapter should deserialize a command,
authenticate the peer, call `dispatch`, then send the returned snapshot/change set. The adapter
must provide encryption, peer verification and rate limiting; the core cannot truthfully provide
network security when it opens no network.

See [`PROTOCOL.md`](PROTOCOL.md) for invariants and adapter acceptance criteria.
