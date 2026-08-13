# Game Screen Streaming Protocol

A signed-in user watches a running game's screen. The Unity SDK captures the
composited screen and publishes it as a WebRTC video track; this server relays
signalling only. **Media never passes through the orchestration server.**

Three parties, two WebSocket connections, one peer connection:

```
Unity SDK  ──/ws/sdk?instanceKey=──▶  orchestration server  ◀──/ws/viewer?instanceId=──  browser
     │                                (signalling relay)                                    │
     └──────────────────────────── WebRTC media (P2P) ────────────────────────────────────▶─┘
```

## Identifiers

| Name | Meaning |
|---|---|
| `instanceKey` | The SDK's durable credential. Used at the `/ws/sdk` handshake and nowhere else. Never appears in a message body, a log line, or a URL the browser sees. |
| `instanceId` | The game instance's opaque id. What the browser addresses and what every server-side map is keyed by. |
| `streamId` | One watching session, minted by this server when a viewer is admitted. |

`streamId` is on every signalling message even though at most one is live at a
time. It is what lets both ends discard signalling that belongs to a session
that has already ended. Without it, an ICE candidate still in flight from a
torn-down peer is indistinguishable from one belonging to the peer that
replaced it, and applying it corrupts the new negotiation. Under the takeover
policy below, that overlap is the normal case rather than a rare one.

## Roles

**The SDK offers.** The browser answering avoids it having to declare a
`recvonly` transceiver up front, and keeps the media-direction decision on the
side that owns the source.

## Viewer admission

One viewer per instance, **newest wins**.

- No SDK connected for that instance → close `4404`.
- Caller is not a member of the owning project → close `4403`.
- A viewer already holds the instance → the incumbent is closed with `4009` and
  the newcomer is admitted with a fresh `streamId`.

Taking over rather than refusing is deliberate, and it is the opposite of what
`/ws/sdk` does for duplicate SDK connections. There, the incumbent is a running
game whose session is expensive to lose. Here the incumbent is a browser tab,
and the common case is one person reloading or reopening the page — making them
wait out their own stale lease to see their own game is the worse failure.

A displaced viewer must **not** reconnect automatically. Two tabs that both
retry on being displaced evict each other indefinitely, burning a peer setup on
the game each round. `4009` is terminal; recovery is a user action.

## Lease

`STREAM_START` carries `leaseSeconds`. The browser sends `RENEW` every 10s; the
server forwards it as `STREAM_RENEW`; the SDK runs its own timer per `streamId`
and tears the peer down when it expires. The same value is the server's receive
timeout on `/ws/viewer`, so one number governs both ends.

The timer lives in the SDK on purpose. A clean disconnect is not the case worth
designing for — a closed laptop lid, a killed browser, or this server going down
all leave the game encoding video for nobody. The SDK stopping on its own is the
only version of "stop when nobody is watching" that does not depend on being
told.

### Sizing the lease

**The lease is a missed-renew tolerance, not a multiple of the renew interval.**
Sizing it against the 10s foreground cadence is what produced the old 15s
default, and 15s does not survive even a single missed renew: the next one is
20s away.

The interval to size against is the throttled one. Browsers clamp timers in
hidden tabs, and the worst documented case is **one wake per minute** — Chrome's
intensive throttling, which applies once a tab has been hidden for five minutes.
An active `RTCPeerConnection` exempts the tab in some browsers and versions, but
that is not a guarantee to design against. So the floor is 60s, and anything
below it cuts a perfectly healthy viewer every cycle the moment its tab goes
behind another window.

The default is **90s** — the throttled minute plus room for scheduling drift and
delivery, and nine foreground renew cycles, so eight consecutive renews can be
lost. Going further, to 120s, would only help a tab that misses a wake-up
entirely; a tab that misses one is frozen rather than throttled, and a frozen tab
is painting no video for anyone. `MINIMUM_LEASE_SECONDS` rejects anything below
61s outright, because below that the tolerance is zero.

### What a longer lease costs

Nothing on the normal path. A viewer socket that closes stops the stream
immediately, through `doFinally` in `ViewerWebSocketHandler` — the lease is not
consulted, and raising it does not delay that by a millisecond.

The lease only ever runs out for a viewer that **vanished without closing its
socket**: a closed laptop lid, a killed browser, a dropped network. For those,
the lease is exactly how long the game keeps encoding and sending video to
nobody. Raising the default from 15s to 90s lengthens that window by 75s. That is
the trade being made — a rare wasted 90 seconds of encoding, against a common
viewer being disconnected every 15.

Deployments that weight it differently set `ARTEL_STREAM_LEASE_SECONDS`. Note
that `/ws/**` reaches this server through a reverse proxy, whose idle timeout
caps the real survival time independently: a throttled viewer sends one frame a
minute and the server sends nothing between events, so a proxy read timeout
shorter than the lease ends the session first, whatever this value says.

## Messages

Every message is JSON with a `type` discriminator, matching the existing
`/ws/sdk` convention.

### Server → SDK

| `type` | Fields |
|---|---|
| `STREAM_START` | `streamId`, `iceServers[]`, `video: { maxWidth, maxFramerate }`, `leaseSeconds` |
| `STREAM_RENEW` | `streamId` |
| `STREAM_STOP` | `streamId` |
| `WEBRTC_ANSWER` | `streamId`, `sdp` |
| `WEBRTC_ICE` | `streamId`, `candidate: { candidate, sdpMid, sdpMLineIndex }` |

`STREAM_START` arriving while a session is live **replaces** it. No preceding
`STREAM_STOP` is sent, so replacement is never an assumption about the ordering
of two messages.

`iceServers` is delivered here rather than compiled into the SDK. The SDK ships
to customers; a hardcoded default would have every customer's game contacting a
third-party STUN host. Configuration keeps that a deployment choice — see
`artel.stream.stun-urls`.

### SDK → Server

| `type` | Fields |
|---|---|
| `WEBRTC_OFFER` | `streamId`, `sdp` |
| `WEBRTC_ICE` | `streamId`, `candidate` |
| `STREAM_STATE` | `streamId`, `state`, `error` (nullable) |

`state` is one of `CONNECTING`, `LIVE`, `FAILED`, `STOPPED`.

`FAILED` must be reported when ICE gives up, not left to time out. Without
TURN, a game and a browser on different networks negotiate successfully and
then carry no media; if that surfaces as an endless "connecting" the user files
a network limitation as a broken stream.

### Browser → Server

| `type` | Fields |
|---|---|
| `RENEW` | — |
| `STOP` | — |
| `WEBRTC_ANSWER` | `streamId`, `sdp` |
| `WEBRTC_ICE` | `streamId`, `candidate` |

### Server → Browser

| `type` | Fields |
|---|---|
| `STREAM_READY` | `streamId`, `iceServers[]` |
| `WEBRTC_OFFER` | `streamId`, `sdp` |
| `WEBRTC_ICE` | `streamId`, `candidate` |
| `STREAM_STATE` | `streamId`, `state`, `error` |
| `ERROR` | `code`, `message` |

## Close codes

| Code | Side | Meaning |
|---|---|---|
| `4001` | SDK | Missing or invalid instance key (pre-existing) |
| `4002` | SDK | Instance already connected (pre-existing) |
| `4003` | Viewer | Missing or malformed `instanceId` |
| `4009` | Viewer | Taken over by a newer viewer |
| `4403` | Viewer | Not a member of the owning project |
| `4404` | Viewer | No SDK connected for this instance |

## Auth

`/ws/viewer` is **not** in the `permitAll` list in `SecurityConfig`;
`anyExchange().authenticated()` covers it, so the handshake is authenticated by
the same `artel_access_token` cookie as every other browser call. No token is
accepted in the query string — that would put a credential into server logs.

The cookie is `SameSite=Lax`, so this works only while the client and this
server share a registrable domain. That is a deployment constraint, not an
accident: split them across domains and every viewer handshake silently loses
its cookie and 401s.

## Configuration

```yaml
artel:
  stream:
    enabled: ${ARTEL_STREAM_ENABLED:true}
    # 갱신 유실 허용 폭. 숨겨진 탭의 1분 갱신 주기 기준이며 61 미만은 거절된다.
    lease-seconds: ${ARTEL_STREAM_LEASE_SECONDS:90}
    # 쉼표 구분. 후보가 늘수록 연결이 느려지므로 1~2개면 충분하다.
    stun-urls: ${ARTEL_STUN_URLS:stun:stun.l.google.com:19302}
    # 대칭 NAT나 UDP를 막는 방화벽에서만 필요하다. 빈 값은 "없음"으로 읽는다.
    turn-url: ${ARTEL_TURN_URL:}
    turn-username: ${ARTEL_TURN_USERNAME:}
    turn-credential: ${ARTEL_TURN_CREDENTIAL:}
```

STUN는 리스트가 아니라 쉼표 문자열로 받는다. `List` 바인딩은 환경변수로 덮으려면
`ARTEL_..._0_URLS` 같은 인덱스 변수를 써야 하는데, 배포는 `.env` 한 장으로 이뤄지므로
그 형태로는 실제로 바꿀 수가 없다.

The default is Google's public STUN host. It is not a service Google offers —
it is their own infrastructure that happens to be unauthenticated, with no
terms and no notice if it changes — and the practical hazard is rate limiting
rather than an outage. With no TURN to fall back to, a throttled STUN shows up
as a plain connection failure. Fine for development; for deployment run coturn
in STUN-only mode, which relays no media and is therefore one stateless UDP
port, and which is also where TURN gets switched on if it is ever needed.
