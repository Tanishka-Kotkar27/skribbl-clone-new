# Skribbl.io Clone

A real-time multiplayer drawing and guessing game. One player draws a word on a
shared canvas while everyone else races to guess it; points go to the quickest,
and the highest score after all rounds wins.

The interface is branded **Inkling**; the name lives in `frontend/src/components/Logo.tsx` and `frontend/index.html` if you want to change it.

**Stack:** React 19 + TypeScript + Vite · Spring Boot 3.5 (Java 21) · MySQL 8 ·
STOMP over WebSocket

> **Live URL:** https://skribbl-clone-new-0nx3.onrender.com &nbsp;← *replace with your Railway address after deploying*

---

## Current status — all 8 phases complete

| Phase | Scope | Status |
|---|---|---|
| 1 | Project setup & architecture | ✅ Done |
| 2 | Database schema & JPA entities | ✅ Done |
| 3 | WebSocket layer & game state engine | ✅ Done |
| 4 | REST APIs for room & lobby | ✅ Done — create, join, get, start, leave |
| 5 | React lobby & room UI | ✅ Done |
| 6 | Canvas drawing + real-time sync | ✅ Done |
| 7 | Chat, guessing & scoring | ✅ Done |
| 8 | Testing, deployment & README | ✅ Done |

See [ROADMAP.md](ROADMAP.md) for the time-boxed plan,
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design, and
[docs/SCHEMA.md](docs/SCHEMA.md) for the database.

---

## Quick start

### Prerequisites

- **JDK 21+** — `java -version`
- **Maven 3.9+** — `mvn -v`
- **Node 20+** — `node -v`
- **Docker** (for MySQL) — or a local MySQL 8 instance

### 1. Start MySQL

```bash
docker compose up -d
docker compose ps          # wait until the db container is "healthy"
```

Creates database `skribbl` with user `skribbl` / password `skribbl` on port **3307** (3306 is left free for any MySQL you already have installed).

<details>
<summary>Without Docker</summary>

```sql
CREATE DATABASE skribbl CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'skribbl'@'localhost' IDENTIFIED BY 'skribbl';
GRANT ALL PRIVILEGES ON skribbl.* TO 'skribbl'@'localhost';
FLUSH PRIVILEGES;
```

Then override the connection with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.
</details>

### 2. Start the backend

```bash
cd backend
mvn spring-boot:run
```

Runs on **http://localhost:8080**. Verify:

```bash
curl http://localhost:8080/api/health
# {"status":"UP","activeRooms":0,"activeSockets":0,"serverTime":...}
```

### 3. Start the frontend

```bash
cd frontend
cp .env.example .env.local
npm install
npm run dev
```

Runs on **http://localhost:5173**.

### 4. Verify the WebSocket path

This is the Phase 1 acceptance test, and it is worth doing carefully — every
later phase builds on this one pipe.

1. Open http://localhost:5173, enter a name, click **Create room**.
2. In the lobby, the connection badge should turn green and read `connected`.
3. Type a message in the chat box. It should appear in every open tab.
4. Copy the invite link into a **second browser tab** and join with another name.
5. Both tabs should show both players in the list, updating live.

If step 3 works, React → STOMP → Spring → private queue → React is proven
end to end.

---

## Project structure

```
skribbl-clone/
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/skribbl/
│       │   ├── SkribblApplication.java
│       │   ├── config/        WebSocketConfig, WebConfig (CORS), AppProperties
│       │   ├── game/          Room, Player, Game, GameSettings, Stroke, RoomRegistry
│       │   ├── ws/            MessageHandler, RoomBroadcaster, SessionRegistry, EventType
│       │   ├── rest/          RoomController, HealthController, GlobalExceptionHandler
│       │   ├── service/       RoomService, PersistenceService, WordService
│       │   ├── domain/        Room/Player/Round/Word/ChatMessage entities
│       │   └── repository/    Spring Data repositories
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── data/words.csv   244 seed words, 10 categories
│       └── test/java/com/skribbl/
│           ├── GameLogicTest.java
│           ├── WordSelectionTest.java
│           └── PersistenceMappingTest.java
├── frontend/
│   └── src/
│       ├── api/      client.ts (REST), session.ts
│       ├── ws/       socket.ts (STOMP wrapper)
│       ├── pages/    Home.tsx, Room.tsx
│       └── types/    events.ts, game.ts
├── infra/init/       MySQL bootstrap SQL
├── docs/ARCHITECTURE.md
├── docker-compose.yml
└── ROADMAP.md
```

---

## API reference

### REST

| Method | Path | Body | Returns |
|---|---|---|---|
| `GET` | `/api/health` | — | liveness + active room/socket counts |
| `POST` | `/api/rooms` | `{ hostName, settings }` | `201` + `JoinRoomResponse` |
| `POST` | `/api/rooms/{code}/join` | `{ playerName }` | `JoinRoomResponse` |
| `GET` | `/api/rooms/{code}` | — | `RoomState` |
| `POST` | `/api/rooms/{code}/start` | `{ playerId }` | `202`; `403` not host, `409` not enough players / already running |
| `POST` | `/api/rooms/{code}/leave` | `{ playerId }` | `204` |

Settings are validated server-side; out-of-range values return `400` with a
per-field message.

| Setting | Range | Default |
|---|---|---|
| `maxPlayers` | 2–20 | 8 |
| `rounds` | 2–10 | 3 |
| `drawTimeSeconds` | 15–240 | 80 |
| `wordChoices` | 1–5 | 3 |
| `hints` | 0–5 | 2 |

<details>
<summary>curl examples</summary>

```bash
# Create a room
curl -s -X POST http://localhost:8080/api/rooms \
  -H 'Content-Type: application/json' \
  -d '{"hostName":"Nutan","settings":{"rounds":3,"drawTimeSeconds":60}}'

# Validation failure — rounds is above the maximum
curl -s -X POST http://localhost:8080/api/rooms \
  -H 'Content-Type: application/json' \
  -d '{"hostName":"Nutan","settings":{"rounds":99}}'
# {"error":"validation_failed", "fields":{"settings.rounds":"rounds must be at most 10"}}

# Join
curl -s -X POST http://localhost:8080/api/rooms/ABC123/join \
  -H 'Content-Type: application/json' -d '{"playerName":"Asha"}'
```
</details>

### WebSocket

Endpoint `/ws` (SockJS fallback registered at the same path).

| Direction | Destination |
|---|---|
| Client → Server | `/app/room/{code}/{action}` |
| Server → Room | `/topic/room/{code}` |
| Server → One player | `/user/queue/private` |

Every push shares one envelope: `{ type, payload, ts }`. Event names live in
`ws/EventType.java` and `types/events.ts` — change one, change both.

---

## Deployment

### How it is deployed

In production the **whole game is one service**: the Docker build compiles the
React app and places it inside the Spring Boot jar, which serves the pages, the
REST API and the WebSocket from the same address.

```
Browser ──https──▶  https://your-app.up.railway.app
                     ├── /              React app (static files)
                     ├── /api/**        REST
                     └── /ws            STOMP over WebSocket (wss://)
                              │
                              ▼  private network
                         Railway MySQL 8
```

Why one service rather than Vercel for the frontend plus a separate backend:

- **No CORS to configure.** Pages and API share an origin.
- **No `ws://` vs `wss://` mistakes.** The client derives the socket URL from
  the page address, so on an https page it is always `wss://`.
- **Vercel and Netlify cannot host the WebSocket server anyway** — they are
  static/serverless and cannot hold a long-lived connection. The assignment
  notes this too.

The server runs as a **single instance**: rooms and the STOMP broker live in
memory. Scaling out would need sticky sessions or a relay broker; see
`docs/ARCHITECTURE.md`.

### Deploying to Railway

1. Push this repository to GitHub.
2. On [railway.com](https://railway.com), **New Project → Deploy from GitHub repo**
   and pick the repository. Railway finds the `Dockerfile` at the root.
3. In the same project, **Create → Database → MySQL**.
4. Open the app service → **Variables** and add:

   | Variable | Value |
   |---|---|
   | `DB_URL` | `jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
   | `DB_USERNAME` | `${{MySQL.MYSQLUSER}}` |
   | `DB_PASSWORD` | `${{MySQL.MYSQLPASSWORD}}` |

5. App service → **Settings → Networking → Generate Domain**.
6. Add two more variables using that domain, then redeploy:

   | Variable | Value |
   |---|---|
   | `CORS_ORIGINS` | `https://your-app.up.railway.app` |
   | `FRONTEND_URL` | `https://your-app.up.railway.app` |

7. Check `https://your-app.up.railway.app/api/health` returns `"status":"UP"`,
   then open the site on two devices and play a round.

The first build takes several minutes (it downloads Maven and npm dependencies).
Tables and the 244-word pool are created automatically on first start.

### Platform notes

- `server.forward-headers-strategy: framework` makes Spring trust the platform
  proxy's `X-Forwarded-*` headers. Without it the app believes it is on plain
  http, and the WebSocket origin check rejects the browser's `https` origin.
- Railway's trial provides a one-time $5 credit for 30 days, enough for a demo.

## Configuration

Backend (environment variables, all optional locally):

| Variable | Default |
|---|---|
| `PORT` | `8080` |
| `DB_URL` | `jdbc:mysql://localhost:3307/skribbl?...` |
| `DB_USERNAME` / `DB_PASSWORD` | `skribbl` / `skribbl` |
| `CORS_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` |
| `FRONTEND_URL` | `http://localhost:5173` |

Frontend (`.env.local`):

| Variable | Default |
|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` |
| `VITE_WS_URL` | `ws://localhost:8080/ws` |

**In production both must use TLS** — `https://` and `wss://`. An HTTPS page
cannot open an insecure `ws://` socket, and this failure appears only once
deployed.

---

## Tests

```bash
cd backend && mvn test
```

| Test | Covers | Needs |
|---|---|---|
| `GameLogicTest` | turn rotation, host promotion, word masking and hints, guess normalisation and near-miss detection, speed-and-order scoring, canvas undo/clear, disconnect mid-game | nothing |
| `WordSelectionTest` | choice count and distinctness, exclusion of used words, custom-word priority, messy input, empty pool | nothing |
| `GameEngineTest` | full multi-round games with a real scheduler: rotation, auto-pick on timeout, hints, secret word never broadcast, drawer leaving mid-turn, reconnect within grace, game ending once, rule enforcement, word modes, play again | nothing |
| `ChatGuessingTest` | correct guess scores and is announced without the word, earlier guesses score more, all-guessed ends the turn early, messages containing the word are withheld, close-guess hints, drawer can't say the word, guessed players chat privately, cleaning and rate limit | nothing |
| `DrawingServiceTest` | drawer-only drawing, input cleaning, per-message/stroke/turn caps, undo targets the last finished stroke, clear, late-join replay snapshot, new turn resets the canvas | nothing |
| `PersistenceMappingTest` | entity mapping, settings JSON round-trip, foreign keys, leaderboard and ordering queries | H2, automatic |

None of them need a running MySQL, so `mvn test` works even when Docker is being
uncooperative.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `Communications link failure` on boot | MySQL not ready — wait for `docker compose ps` to show healthy |
| Badge stuck on `connecting` | Backend not running, or `VITE_WS_URL` points at the wrong port |
| CORS error in console | Add the frontend origin to `CORS_ORIGINS` and restart the backend |
| `No session for this room` | You opened `/room/{code}` directly — join from the home page |
| `words` table empty | Seeder logs at startup; check `data/words.csv` is on the classpath |
| Player shows "reconnecting…" | Their socket dropped; they have 15 s to come back before being removed |
| Start button does nothing | Only the host sees it, and it needs 2 connected players |
| Port 3306 already in use | Another MySQL is running; map Docker to 3307 and update `DB_URL` |
| Works locally, socket fails in production | `ws://` on an HTTPS page — switch to `wss://` |
| Two tabs act as one player | Session is per tab by design; use a normal second tab, not a duplicate |
