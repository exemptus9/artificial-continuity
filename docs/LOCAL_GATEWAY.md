# Local Continuity Gateway

The local gateway is a loopback-first Node service intended to serve the app and expose independently protected reasoning and capture APIs.

## Intended run shape

```bash
CONTINUITY_GATEWAY_TOKEN='reason-secret' \
CONTINUITY_BRIDGE_TOKEN='capture-secret' \
npm run serve:gateway
```

Default bind address: `127.0.0.1:4173`.

## Endpoints

### `GET /api/health`
Reports gateway availability without revealing tokens.

### `POST /api/reason`
Header: `x-continuity-gateway-token`.

Protocol: `ContinuityReasoning/0.1`.

A local gateway may route requests into an offline reasoner or a selected remote provider while preserving the browser-facing contract.

### `GET /api/capture/inbox`
Header: `x-continuity-bridge-token`.

Returns the local capture inbox. Clients should import only capture IDs not already present in event-derived state.

### `POST /api/capture`
Header: `x-continuity-bridge-token`.

Accepts a `ContinuityCapture/0.1` envelope and appends accepted captures to the inbox.

## Security posture

No endpoint becomes privileged merely because it is localhost. Tokens are still required because arbitrary webpages can often attempt requests to loopback services.

Reasoning credentials and capture-bridge credentials should remain separate.