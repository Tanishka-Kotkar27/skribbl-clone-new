import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, HttpError } from '../api/client';
import { clearSession, loadSession } from '../api/session';
import { canvasStore } from '../canvas/canvasStore';
import ChatPanel, { type ChatLine } from '../components/ChatPanel';
import GamePanel from '../components/GamePanel';
import Icon from '../components/Icon';
import LobbyPanel from '../components/LobbyPanel';
import Logo from '../components/Logo';
import PlayerList from '../components/PlayerList';
import {
  EventType,
  type ChatMessagePayload,
  type GameOverPayload,
  type GuessResultPayload,
  type HintRevealedPayload,
  type RoundEndPayload,
  type RoundStartPayload,
  type SystemMessagePayload,
  type TimerTickPayload,
  type WordChoicesPayload,
  type WordConfirmedPayload,
} from '../types/events';
import type { PlayerView, RoomState } from '../types/game';
import { gameSocket, type SocketStatus } from '../ws/socket';

/**
 * Lobby and game screen for one room. Everything here mirrors server events;
 * the page never decides turns, scores or whether a guess is right.
 */
export default function Room() {
  const { code } = useParams<{ code: string }>();
  const navigate = useNavigate();
  const session = loadSession();

  const [room, setRoom] = useState<RoomState | null>(null);
  const [status, setStatus] = useState<SocketStatus>('disconnected');
  const [error, setError] = useState<string | null>(null);
  const [chat, setChat] = useState<ChatLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [codeCopied, setCodeCopied] = useState(false);

  const [remaining, setRemaining] = useState(0);
  const [roundInfo, setRoundInfo] = useState<RoundStartPayload | null>(null);
  const [choices, setChoices] = useState<string[] | null>(null);
  const [myWord, setMyWord] = useState<string | null>(null);
  const [roundEnd, setRoundEnd] = useState<RoundEndPayload | null>(null);
  const [gameOver, setGameOver] = useState<GameOverPayload | null>(null);

  const lineCounter = useRef(0);

  const addLine = useCallback((line: Omit<ChatLine, 'id'>) => {
    lineCounter.current += 1;
    const next: ChatLine = { ...line, id: lineCounter.current };
    setChat((prev) => [...prev.slice(-150), next]);
  }, []);

  const notice = useCallback(
    (text: string, kind: ChatLine['kind'] = 'system') => addLine({ kind, text }),
    [addLine],
  );

  const updatePlayers = useCallback((players: PlayerView[]) => {
    setRoom((prev) => (prev ? { ...prev, players } : prev));
  }, []);

  // Load the room over REST first, so a refresh renders before the socket is up.
  useEffect(() => {
    if (!code) return;
    let cancelled = false;
    api
      .getRoom(code)
      .then((state) => {
        if (!cancelled) {
          setRoom(state);
          setRemaining(state.remainingSeconds);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) setError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [code]);

  useEffect(() => {
    if (!code) return;
    const stored = loadSession();
    if (!stored || stored.roomCode !== code) {
      setError('You are not in this room yet. Join it from the home page.');
      return;
    }
    const me = stored.playerId;

    const applyState = (state: RoomState) => {
      setRoom(state);
      setRemaining(state.remainingSeconds);
    };

    const off = [
      gameSocket.onStatusChange(setStatus),

      gameSocket.on<RoomState>(EventType.ROOM_STATE, applyState),
      gameSocket.on<RoomState>(EventType.GAME_STATE, applyState),

      gameSocket.on<{ player: PlayerView; players: PlayerView[] }>(EventType.PLAYER_JOINED, (p) => {
        updatePlayers(p.players);
        if (p.player.id !== me) notice(`${p.player.name} joined`);
      }),
      gameSocket.on<{ players: PlayerView[] }>(EventType.PLAYER_LEFT, (p) => updatePlayers(p.players)),
      gameSocket.on<{ hostId: string }>(EventType.HOST_CHANGED, (p) => {
        setRoom((prev) => (prev ? { ...prev, hostId: p.hostId } : prev));
      }),

      gameSocket.on<RoundStartPayload>(EventType.ROUND_START, (p) => {
        setRoundInfo(p);
        setRoundEnd(null);
        setGameOver(null);
        setChoices(null);
        setMyWord(null);
      }),
      gameSocket.on<WordChoicesPayload>(EventType.WORD_CHOICES, (p) => setChoices(p.choices)),
      // Sent to the drawer after choosing, and to a guesser the moment they get it.
      gameSocket.on<WordConfirmedPayload>(EventType.WORD_CONFIRMED, (p) => {
        setMyWord(p.word);
        setChoices(null);
      }),
      gameSocket.on<TimerTickPayload>(EventType.TIMER_TICK, (p) => setRemaining(p.remainingSeconds)),
      gameSocket.on<HintRevealedPayload>(EventType.HINT_REVEALED, (p) => {
        setRoom((prev) => (prev ? { ...prev, maskedWord: p.maskedWord } : prev));
        notice('A letter was revealed');
      }),
      gameSocket.on<RoundEndPayload>(EventType.ROUND_END, (p) => {
        setRoundEnd(p);
        setChoices(null);
        updatePlayers(p.players);
        notice(`The word was ${p.word}`);
      }),
      gameSocket.on<GameOverPayload>(EventType.GAME_OVER, (p) => setGameOver(p)),

      gameSocket.on<ChatMessagePayload>(EventType.CHAT_MESSAGE, (p) => {
        addLine({
          kind: p.kind === 'guess' ? 'guess' : 'chat',
          name: p.playerName,
          text: p.text,
          guessedOnly: p.guessedOnly,
          mine: p.playerId === me,
        });
      }),
      gameSocket.on<GuessResultPayload>(EventType.GUESS_RESULT, (p) => {
        if (p.correct) {
          const who = p.playerId === me ? 'You' : p.playerName;
          addLine({ kind: 'correct', text: `${who} guessed the word (+${p.points ?? 0})` });
        } else if (p.close) {
          addLine({ kind: 'close', text: `“${p.text}” is close!` });
        }
      }),

      gameSocket.on<SystemMessagePayload>(EventType.SYSTEM_MESSAGE, (p) => notice(p.text)),
      gameSocket.on<{ message: string }>(EventType.ERROR, (p) => {
        notice(p.message, 'error');
        setError(p.message);
      }),
    ];

    // Start blank; if a turn is under way the server sends the drawing on join.
    canvasStore.reset();
    gameSocket.connect(code, stored.playerId, stored.playerName);

    return () => {
      for (const unsubscribe of off) unsubscribe();
      gameSocket.disconnect();
    };
  }, [code, addLine, notice, updatePlayers]);

  // Errors fade on their own after a few seconds.
  useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => setError(null), 6000);
    return () => window.clearTimeout(timer);
  }, [error]);

  async function handleStart() {
    if (!code || !session) return;
    setBusy(true);
    setError(null);
    try {
      await api.startGame(code, session.playerId);
    } catch (err) {
      setError(err instanceof HttpError ? err.message : 'Could not start the game.');
    } finally {
      setBusy(false);
    }
  }

  async function handleLeave() {
    if (code && session) {
      // If this fails the server removes us after the reconnect grace period anyway.
      await api.leaveRoom(code, session.playerId).catch(() => undefined);
    }
    gameSocket.disconnect();
    clearSession();
    navigate('/');
  }

  async function copyCode() {
    if (!code) return;
    try {
      await navigator.clipboard.writeText(code);
      setCodeCopied(true);
      setTimeout(() => setCodeCopied(false), 1500);
    } catch {
      // Clipboard blocked; the code is visible in the chip.
    }
  }

  const myId = session?.playerId ?? '';
  const me = room?.players.find((p) => p.id === myId);
  const isHost = Boolean(room && room.hostId === myId);
  const inGame = Boolean(room && room.phase !== 'LOBBY');
  const isDrawer = Boolean(room && room.drawerId === myId);
  const drawing = room?.phase === 'DRAWING';

  const placeholder = !drawing
    ? 'Say something'
    : isDrawer
      ? "Chat (you can't say the word)"
      : me?.guessedCurrentRound
        ? 'Talk to others who got it'
        : 'Type your guess';

  return (
    <>
      <header className="appbar">
        <div className="appbar-inner">
          <div className="appbar-left">
            <Logo size={26} />
            <span className="room-chip">
              Room <strong>{code}</strong>
              <button type="button" className="icon-btn" onClick={copyCode} title="Copy room code" aria-label="Copy room code">
                <Icon name={codeCopied ? 'check' : 'copy'} size={15} />
              </button>
            </span>
          </div>
          <div className="appbar-right">
            {status !== 'connected' && (
              <span className="pill pill-warn">
                <Icon name="wifi-off" size={14} />
                {status === 'connecting' ? 'Connecting…' : 'Reconnecting…'}
              </span>
            )}
            <button type="button" className="btn btn-ghost" onClick={handleLeave}>
              <Icon name="logout" size={17} />
              Leave
            </button>
          </div>
        </div>
      </header>

      {error && (
        <div className="toast" role="alert">
          <span>{error}</span>
          <button type="button" className="icon-btn" onClick={() => setError(null)} aria-label="Dismiss">
            ×
          </button>
        </div>
      )}

      <main className="room">
        {room ? <PlayerList room={room} myId={myId} /> : <aside className="panel players" />}

        <div className="stage-col">
          {room && inGame && session ? (
            <GamePanel
              room={room}
              myId={myId}
              isHost={isHost}
              remaining={remaining}
              roundInfo={roundInfo}
              choices={choices}
              myWord={myWord}
              roundEnd={roundEnd}
              gameOver={gameOver}
              busy={busy}
              onChooseWord={(word) => gameSocket.send('word-chosen', { word })}
              onPlayAgain={handleStart}
            />
          ) : room ? (
            <LobbyPanel room={room} isHost={isHost} busy={busy} onStart={handleStart} />
          ) : (
            <section className="panel lobby">
              <p className="muted">Loading room…</p>
            </section>
          )}
        </div>

        <ChatPanel
          lines={chat}
          placeholder={placeholder}
          disabled={status !== 'connected'}
          onSend={(text) => gameSocket.send('chat', { text })}
        />
      </main>
    </>
  );
}
