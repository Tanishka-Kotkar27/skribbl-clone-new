# Architecture

This is the document to read before the code walkthrough. It covers the four
things the brief says you must be able to explain: stroke capture and sync, game
state management, WebSocket usage, and word matching.

---

## 1. System shape

```
┌──────────────────────────────┐         ┌────────────────────────────────────┐
│  Browser (React + TS + Vite) │         │   Spring Boot 3.5 (Java 21)        │
│                              │         │                                    │
│  Home ─── create/join ───────┼──HTTP──▶│  RoomController ──▶ RoomService     │
│                              │         │                         │          │
│  Room ─┬─ CanvasBoard        │         │                         ▼          │
│        ├─ ChatPanel          │◀─STOMP─▶│  MessageHandler ──▶ RoomRegistry    │
│        └─ Scoreboard         │   /ws   │        │                 │          │
│                              │         │        ▼                 ▼          │
│  GameSocket (one per tab)    │         │  RoomBroadcaster      Room ──▶ Game │
└──────────────────────────────┘         │                          │         │
                                         │                          ▼         │
                                         │              ScheduledExecutor     │
                                         │              (round + hint timers) │
                                         │                          │         │
                                         │                          ▼         │
                                         │              Spring Data JPA ──▶ MySQL 8
                                         └────────────────────────────────────┘
```

---

## 2. Why two transports

The application deliberately uses **both** HTTP and WebSocket, split by the
shape of the interaction rather than by convenience.

| | REST | WebSocket (STOMP) |
|---|---|---|
| **Used for** | create room, join room, fetch lobby state, start game | strokes, guesses, chat, timers, round transitions |
| **Shape** | request → response, one caller | server-initiated push, many receivers |
| **Why** | happens *before* a socket exists; benefits from status codes, Bean Validation, and being retryable | broadcast-shaped; polling would be both slower and far more expensive |

The dividing line is simple: **if it happens before you are in the room, it is
REST. Once you are in the room, it is WebSocket.**

A second reason matters for the demo: REST endpoints can be tested with `curl`
before any UI exists, which is why Phase 4 is scheduled before Phase 5.

## 3. Why STOMP rather than raw WebSocket

A raw `WebSocketHandler` would mean hand-writing a message envelope, a
subscription registry, a fan-out loop, and per-user routing. STOMP provides all
four:

- **Subscription routing** — clients subscribe to `/topic/room/ABC123` and Spring
  handles fan-out. No manual set of sessions per room to keep in sync.
- **Per-user destinations** — `/user/queue/private` is rewritten per session.
  This is not a nicety: it is how the drawer receives the secret word without it
  reaching the guessers.
- **Annotation-driven dispatch** — `@MessageMapping("/room/{code}/draw-move")`
  replaces a hand-rolled `switch` on a message type field.
- **Heartbeats** — built in, so dead sockets are detected rather than lingering.

The cost is a framing layer on the wire and a client library. At this scale that
is a clear win. **SockJS** is registered as a fallback endpoint server-side for
networks that block WebSocket upgrades; the browser client uses the native
socket, which avoids SockJS's CommonJS `global` shim in Vite.

### Destination map

| Direction | Destination | Purpose |
|---|---|---|
| Client → Server | `/app/room/{code}/{action}` | every inbound message |
| Server → Room | `/topic/room/{code}` | everything the whole room may see |
| Server → One player | `/user/queue/private` | word choices, the confirmed word, errors |

Every server push uses one envelope, so the client has one subscription and one
switch rather than a subscription per event kind:

```json
{ "type": "draw_data", "payload": { "...": "..." }, "ts": 1758210000000 }
```

---

## 4. Object model

The brief asks for an OOP structure. The guiding rule here is that **game rules
never touch the transport**, which is what makes the hardest logic testable
without a socket.

| Class | Owns | Deliberately does *not* own |
|---|---|---|
| `Player` | identity, score, connection state, per-round flags | anything about other players |
| `Room` | membership, host, settings, stroke history, the lock | round logic, scoring |
| `Game` | phase machine, turn rotation, timers, scoring, word masking | sockets, HTTP, persistence |
| `RoomRegistry` | the live rooms map, code generation, eviction | what happens inside a room |
| `MessageHandler` | parse → authorise → delegate → broadcast | any game rule |
| `RoomBroadcaster` | the single outbound exit point | deciding *what* to send |
| `SessionRegistry` | socket id → (room, player) | game state |
| `RoomService` | room lifecycle used by REST and the engine | message formatting |

`Game` having no Spring imports at all is the load-bearing decision:
`GameLogicTest` exercises turn rotation, scoring, masking and disconnect
handling in milliseconds, with no context to boot.

---

## 5. Game state and concurrency

State lives in memory during a round and is persisted at round boundaries.
Writing to MySQL on every stroke would add a database round-trip to the hot path
for no benefit — the strokes are transient, and the canvas is already replayable
from `Room.strokeHistory`.

A room is touched by at least three thread pools:

1. **HTTP worker threads** — REST calls
2. **WebSocket inbound threads** — STOMP frames
3. **Scheduler threads** — round timer, hint reveals, per-second ticks

So every mutation goes through **one lock per room** (`Room.lock()`). Four rules
keep this correct:

1. Any code that mutates room or game state takes that room's lock first.
2. A timer callback takes the same lock before touching state — it is just
   another writer.
3. `Game.cancelTimers()` runs before every new turn. Skipping this leaves the
   previous round's timer armed; it fires during the next round and ends it
   early. This is the most common bug in this kind of game loop.
4. **The database is never touched while holding the lock.** Broadcasting,
   however, *is* done inside it, on purpose: it keeps every client's event
   stream in the same order as the state changes. That is safe because
   `convertAndSend` only enqueues onto Spring's outbound channel — it does not
   wait for any client's network — so one slow browser cannot stall the room.

### The engine

`GameEngine` drives every room. It has **no Spring, socket or database
imports**: it talks outward through three small interfaces — `GameEvents`
(send to room / to one player), `WordProvider` (word pool) and `GameRecorder`
(history) — and takes its scheduler and timings as constructor arguments.
`GameEngineConfig` plugs in the real implementations; `GameEngineTest` plugs in
fakes and plays complete games in about two seconds.

Three mechanisms keep it correct under concurrency:

- **Turn tokens.** Every scheduled callback carries the id of the turn that
  created it and does nothing if the room has moved on. Cancelling a
  `ScheduledFuture` is not enough: a callback already blocked waiting for the
  room lock cannot be cancelled, and would end the *next* turn early.
- **Self-contained timer callbacks.** Each catches its own exceptions. An
  exception escaping a `scheduleAtFixedRate` task silently cancels all future
  runs — the countdown would just freeze with nothing in the logs.
- **Asynchronous history.** `DbGameRecorder` hands every write to one background
  thread. One thread, not a pool, so writes land in the order they happened; the
  round's database id is passed between writes through `TurnRecord.dbId`.

**Disconnects get a 15-second grace period.** A dropped socket marks the player
"reconnecting" instead of removing them, so a page refresh does not throw anyone
out. A drawer who refreshes is re-sent their word privately on reconnect. If
they are not back in time they are removed; if they were drawing, the turn ends
and the next player draws — the rotation is adjusted so that player is not
skipped. A late "socket closed" from an old connection is ignored once the same
player has reconnected on a new one.

**The drawer's identity comes from the socket, not the message.** When a word
choice arrives, the player is looked up from the STOMP session binding. Trusting a
player id in the message body would let any guesser choose the word.

### Round lifecycle

```
LOBBY ──host starts──▶ CHOOSING ──drawer picks word──▶ DRAWING
                          ▲                               │
                          │                    timer expires, or
                          │                 every guesser is correct
                          │                               ▼
                          └──rounds remaining────── ROUND_END
                                                          │
                                                   last round done
                                                          ▼
                                                     GAME_OVER
```

A *round* is one full cycle in which every player draws once, matching
skribbl.io. Three rounds with four players is twelve drawing turns.

### Disconnects

`StompEventListener` catches `SessionDisconnectEvent`, looks the session up in
`SessionRegistry`, removes the player and — critically — removes them from the
turn rotation. Without that last step the game can hand a drawing turn to a
player who has left, and the round never ends.

---

## 6. Canvas sync

**Capture.** The drawer's client listens for `pointerdown` / `pointermove` /
`pointerup`. Each point is converted to a **normalised 0.0–1.0 coordinate**
relative to the canvas before it leaves the browser.

Normalising is not a detail. Clients have different canvas sizes; pixel
coordinates would render at the wrong position on every screen but the drawer's,
and the bug only appears when a second machine joins.

**Transport.** Points are **batched** — sent about every 30 ms — rather than one
message per `pointermove`. A raw pointermove stream is hundreds of events per
second per drawer and would saturate the socket. Before batching, the client
also drops points closer than 0.15% of the canvas to the previous one, and uses
`getCoalescedEvents()` to recover the positions the browser merged into a single
event, so fast curves stay smooth rather than turning into straight segments.
Coordinates are rounded to 4 decimal places — sub-pixel on any screen, and about
half the JSON of a full double.

```
drawer: pointerdown ──▶ draw_start { strokeId, x, y, color, size, eraser }
        pointermove ──▶ draw_move  { strokeId, points: [{x,y}, …] }   (batched)
        pointerup   ──▶ draw_end   { strokeId }
```

**Fan-out.** `DrawingService` appends the points to the stroke and rebroadcasts
them as `draw_data` (`kind: start | move | end`) to `/topic/room/{code}`,
including back to the drawer, as the brief suggests. Viewers draw only the new
segment on each `move`, not the whole picture.

The drawer has already drawn its own stroke locally, with no network delay, so
its client skips the echo of stroke ids it created. Everything else —
especially undo and clear — is applied from the server's message by every
client, the drawer included, so the canvases cannot drift apart.

**Ordering.** Spring normally spreads a client's incoming frames across a thread
pool, so two `draw_move` batches from one drawer could be handled out of order
and the line would zig-zag. `setPreserveReceiveOrder(true)` processes each
client's frames in the order sent, and `setPreservePublishOrder(true)` delivers
to each client in the order published.

**History.** The room keeps finished strokes in order, plus any stroke whose
pen is still down:
- **Undo** removes the last *finished* stroke — never the one being drawn — and
  broadcasts its id. Every client deletes that id and repaints.
- **Clear** empties everything (drawer only).
- **Late joiners and refreshes** are sent the whole drawing privately as
  `canvas_replay`, including a stroke still in progress, which keeps growing as
  its next points arrive.

On the client, strokes live in `canvasStore`, not in the canvas component. The
store subscribes when the app loads, before any socket exists. This matters: the
replay can arrive before React has mounted the canvas, and a component that
only subscribed on mount would miss it and show a blank board.

**Authorisation and limits.** Only the current drawer, during the drawing phase,
can draw, undo or clear; the drawer is identified from the socket session, not
the message. Every value is cleaned before it is stored or relayed — coordinates
clamped to 0–1, brush size to 1–60, colour to `#rrggbb` — and hard caps apply:
500 points per message, 5,000 per stroke, 20,000 per turn. A modified client
cannot push megabytes at the rest of the room. Rejected stroke messages are
dropped without a reply, since answering every stray pointermove with an error
would only flood the socket.

---

## 7. Word matching

Both sides of the comparison go through the same normalisation
(`Game.normalise`):

1. `trim()`
2. `toLowerCase(Locale.ROOT)`
3. strip punctuation — everything outside `[a-z0-9\s-]`
4. collapse internal whitespace runs to a single space

So `"  Ice   CREAM!  "` matches `"ice cream"`. Spaces and hyphens are also
ignored when comparing, so `icecream` and `ice-cream` count for "ice cream" —
spacing is not what the player is being tested on.

Beyond exact matching, `isCloseGuess` reports a Levenshtein distance of exactly
1, letting the UI say "close!" without awarding points. That is the detail that
makes the clone feel like the original, and it costs about fifteen lines.

**Hints** reveal one random letter at a time on a timer, never exceeding half the
word's letters regardless of the configured hint count — otherwise a short word
becomes readable and the round stops being a game.

The secret word travels only on the drawer's private queue. `RoomStateResponse`
carries `maskedWord` and has no field for the plain word at all, which makes the
leak structurally impossible rather than merely avoided.

### Guesses and chat share one box

As in skribbl.io there is one input. The browser sends everything as `chat`
and **the server decides** what it was — the client never holds the answer, so
it cannot be read out of the page. `GameEngine.handleChat` applies these rules
during a drawing turn:

| Who / what | Result |
|---|---|
| Guesser types the word | Points; room sees "Bilal guessed the word! +180" (never the word); the word is sent privately to Bilal; turn ends early if everyone has now guessed |
| Guesser's message *contains* the word ("is it rocket?") | Shown to nobody; private note to the sender. Otherwise it would be a wrong guess, broadcast, and give the answer away |
| Guesser is one letter off | Shown as a normal guess, plus a private "close!" |
| Anything else from a guesser | Shown to everyone as a guess |
| Player who already guessed | Seen only by the drawer and others who guessed, so they cannot hint |
| Drawer says the word, even spaced out ("r o c k e t") | Blocked, private note |

Outside a drawing turn everything is plain chat. Lines are trimmed, stripped
of control characters, capped at 100 characters and rate-limited to one per
300 ms per player.

**Scoring:** a correct guess earns up to 200 points for speed (proportional to
time left) plus an order bonus of 50 for the first guesser, falling by 10 for
each after; never less than 25. The drawer earns 25 for every player who
guesses, so both an unreadable scribble and giving the word away cost them.

The key test (`ChatGuessingTest`, and the full-game simulation behind it) plays
turns where bots send wrong guesses, sneaky guesses containing the word, the
drawer trying to say it, and the answer — then checks that no room-wide message
contains the word between that turn's start and the moment it is revealed.

---

## 8. Persistence

| Table | Holds |
|---|---|
| `rooms` | code, host, settings JSON, status |
| `players` | name, score, host flag, room FK |
| `rounds` | round number, drawer, word, status |
| `words` | the word pool, with category |
| `chat_messages` | guesses and chat, for history |

Settings live in a single JSON column rather than a normalised settings table:
they are read and written as one blob, never queried by field, so normalising
would buy nothing and cost an hour of mapping code.

---

## 9. Scaling, and what would have to change

The current design runs in **one JVM** with a simple in-memory broker and an
in-memory room registry. That is the right call for this assignment and for any
single-instance deployment.

Running more than one instance would break two assumptions — rooms live in one
process's heap, and the simple broker only knows its own sessions. Fixing it
means either:

- **sticky sessions** routing a room's players to the same instance, plus a
  shared registry (Redis) for room lookup; or
- a **relay broker** (RabbitMQ / ActiveMQ) so any instance can broadcast to a
  room's topic, with room state moved to Redis.

Worth knowing for the interview. Not worth building for the deadline.
