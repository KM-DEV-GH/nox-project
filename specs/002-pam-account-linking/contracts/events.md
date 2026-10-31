# Message Contract: Partner Link Events

Versioned contract for messages NOX publishes to RabbitMQ (constitution Principles V and XI).
Breaking changes ship under a new routing-key version (`.v2`); the `.v1` keys below stay stable.

## Transport

| Property | Value |
|---|---|
| Broker | RabbitMQ |
| Exchanges | `partnerlink.events` (user notifications), `partnerlink.webhooks` (partner webhook events) — both `topic`, durable |
| Delivery | Persistent messages, publisher confirms, at-least-once via Spring Modulith's event publication registry |
| Ownership | NOX declares only the two exchanges. Consumers declare and bind their own queues and dead-letter exchanges |
| Content type | `application/json`, UTF-8, lowerCamelCase keys |

## Required AMQP properties / headers on every message

| Name | Where | Description |
|---|---|---|
| `messageId` | AMQP `message_id` property | Unique per message (UUID). Consumers use it to deduplicate |
| `traceId` | header | Same value as `meta.traceId` in API responses and in logs for the originating request |
| `correlationId` | AMQP `correlation_id` property | The `PartnerLinkRequest.correlation_id` that started the link |
| `type` | AMQP `type` property | The routing key, e.g. `partner-link.approved.v1` |

## Envelope

Every body uses the same envelope as API responses (Principle V):

```json
{
  "data": { },
  "meta": {
    "traceId": "a0b3dd10e3be3097d588b6c3ccd7f4c0",
    "timestamp": "2026-10-26T09:21:41.942967Z",
    "instance": "partner-link.approved.v1"
  }
}
```

`meta.instance` holds the routing key, since there is no request path.

## Events

### `partner-link.approval-requested.v1` — exchange `partnerlink.events`

Sent when a `PENDING` link is created; asks the user to approve or reject (spec FR-006).

```json
{
  "data": {
    "linkId": "6c2e29b0-0760-4f15-985b-16876010da10",
    "userId": "3f6c1a2e-6b1d-4c1e-9a77-0d6f7c2b9e11",
    "partnerName": "Example partner",
    "licenseeId": "nox-gaming-eu",
    "expiresAt": "2026-10-29T09:21:41Z"
  },
  "meta": { "traceId": "…", "timestamp": "…", "instance": "partner-link.approval-requested.v1" }
}
```

### `partner-link.approved.v1` — exchange `partnerlink.events` (user)

```json
{
  "data": {
    "linkId": "6c2e29b0-0760-4f15-985b-16876010da10",
    "userId": "3f6c1a2e-6b1d-4c1e-9a77-0d6f7c2b9e11",
    "partnerName": "Example partner",
    "occurredAt": "2026-10-26T10:02:11Z"
  },
  "meta": { "traceId": "…", "timestamp": "…", "instance": "partner-link.approved.v1" }
}
```

### `partner-link.approved.v1` — exchange `partnerlink.webhooks` (partner)

```json
{
  "data": {
    "partnerClientId": "demo-partner",
    "requestId": "6f1d0c52-3b0e-4a4f-9a38-1d0c1c7a2e10",
    "externalId": "76490253-9de4-4988-b757-ad0f13a5c7fd",
    "linkId": "6c2e29b0-0760-4f15-985b-16876010da10",
    "status": "CONNECTED",
    "occurredAt": "2026-10-26T10:02:11Z"
  },
  "meta": { "traceId": "…", "timestamp": "…", "instance": "partner-link.approved.v1" }
}
```

`linkId` is present only on this event (spec FR-011).

### `partner-link.rejected.v1` — exchange `partnerlink.events` (user)

```json
{
  "data": {
    "linkId": "6c2e29b0-0760-4f15-985b-16876010da10",
    "userId": "3f6c1a2e-6b1d-4c1e-9a77-0d6f7c2b9e11",
    "partnerName": "Example partner",
    "occurredAt": "2026-10-26T10:02:11Z"
  },
  "meta": { "traceId": "…", "timestamp": "…", "instance": "partner-link.rejected.v1" }
}
```

### `partner-link.rejected.v1` — exchange `partnerlink.webhooks` (partner)

```json
{
  "data": {
    "partnerClientId": "demo-partner",
    "requestId": "6f1d0c52-3b0e-4a4f-9a38-1d0c1c7a2e10",
    "externalId": "76490253-9de4-4988-b757-ad0f13a5c7fd",
    "status": "REJECTED",
    "occurredAt": "2026-10-26T10:02:11Z"
  },
  "meta": { "traceId": "…", "timestamp": "…", "instance": "partner-link.rejected.v1" }
}
```

No `linkId` — no connection was established.

## Not published

Revocation and expiry produce no messages (spec FR-011). `PartnerLinkRequestSubmitted` is an internal
event that triggers the asynchronous Owl lookup and is never externalized.

## Consumer obligations (informative)

Consumers must be idempotent on `messageId`, use manual acknowledgement, and dead-letter messages
that fail after bounded retries (Principle XI). These belong to the consuming services, not NOX.
