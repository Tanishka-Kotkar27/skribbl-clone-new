import type { PlayerView } from './game';

/**
 * The WebSocket wire contract.
 *
 * These names mirror `backend/src/main/java/com/skribbl/ws/EventType.java`
 * exactly. If you add an event, add it in both places — that duplication is
 * deliberate, since code-generating it would cost more setup than it saves at
 * this project's size.
 */
export const EventType = {
  // Room & lobby
  ROOM_STATE: 'room_state',
  PLAYER_JOINED: 'player_joined',
  PLAYER_LEFT: 'player_left',
  HOST_CHANGED: 'host_changed',

  // Game state
  GAME_STATE: 'game_state',
  ROUND_START: 'round_start',
  WORD_CHOICES: 'word_choices',
  WORD_CONFIRMED: 'word_confirmed',
  TIMER_TICK: 'timer_tick',
  HINT_REVEALED: 'hint_revealed',
  ROUND_END: 'round_end',
  GAME_OVER: 'game_over',

  // Drawing
  DRAW_START: 'draw_start',
  DRAW_MOVE: 'draw_move',
  DRAW_END: 'draw_end',
  DRAW_DATA: 'draw_data',
  CANVAS_CLEAR: 'canvas_clear',
  DRAW_UNDO: 'draw_undo',
  CANVAS_REPLAY: 'canvas_replay',

  // Chat & guessing
  GUESS: 'guess',
  GUESS_RESULT: 'guess_result',
  CHAT: 'chat',
  CHAT_MESSAGE: 'chat_message',
  SYSTEM_MESSAGE: 'system_message',

  // Infrastructure
  PONG: 'pong',
  ERROR: 'error',
} as const;

export type EventName = (typeof EventType)[keyof typeof EventType];

/** Envelope every server push arrives in. */
export interface ServerEvent<T = unknown> {
  type: EventName;
  payload: T;
  ts: number;
}

/** Normalised canvas coordinate, 0.0 to 1.0 on both axes. */
export interface Point {
  x: number;
  y: number;
}

export interface Stroke {
  id: string;
  playerId: string;
  color: string;
  size: number;
  eraser: boolean;
  points: Point[];
}

// ---------------------------------------------------------------------------
// Game-flow payloads (Phase 3). Shapes mirror what GameEngine.java sends.
// ---------------------------------------------------------------------------


/** Room-wide: a new drawing turn has begun. */
export interface RoundStartPayload {
  round: number;
  totalRounds: number;
  drawerId: string;
  drawerName: string;
  choiceTimeoutSeconds: number;
}

/** Private to the drawer: pick one of these. */
export interface WordChoicesPayload {
  choices: string[];
  timeoutSeconds: number;
}

/** Private to the drawer: the word they are drawing. */
export interface WordConfirmedPayload {
  word: string;
}

export interface TimerTickPayload {
  remainingSeconds: number;
}

export interface HintRevealedPayload {
  maskedWord: string;
  hintsRemaining: number;
}

export type EndReason = 'TIME_UP' | 'ALL_GUESSED' | 'DRAWER_LEFT';

/** Room-wide: the turn is over and the word is revealed. */
export interface RoundEndPayload {
  word: string;
  reason: EndReason;
  drawerId: string;
  drawerPoints: number;
  correctGuessCount: number;
  players: PlayerView[];
  nextDrawerId: string | null;
  gameOver: boolean;
  pauseSeconds: number;
}

export interface GameOverPayload {
  winner: PlayerView | null;
  winners: PlayerView[];
  leaderboard: PlayerView[];
}

export interface SystemMessagePayload {
  text: string;
}

// ---------------------------------------------------------------------------
// Drawing payloads (Phase 6). Shapes mirror DrawingService.java.
// ---------------------------------------------------------------------------

/** Room-wide: one piece of a stroke. `start` carries the brush; `move` carries new points only. */
export interface DrawDataPayload {
  kind: 'start' | 'move' | 'end';
  strokeId: string;
  playerId: string;
  color?: string;
  size?: number;
  eraser?: boolean;
  points?: Point[];
}

export interface DrawUndoPayload {
  strokeId: string;
}

/** A stroke as sent in a late-join replay. */
export interface ReplayStroke extends Stroke {
  /** False if the drawer's pen was still down when the replay was taken. */
  complete: boolean;
}

/** Private to one player: the whole drawing so far. */
export interface CanvasReplayPayload {
  strokes: ReplayStroke[];
}

// ---------------------------------------------------------------------------
// Chat and guessing payloads (Phase 7). Shapes mirror GameEngine.handleChat.
// ---------------------------------------------------------------------------

/**
 * A chat line. `kind` is `guess` for a wrong guess from someone still guessing,
 * `chat` for everything else. `guessedOnly` lines were visible only to the
 * drawer and players who had already guessed.
 */
export interface ChatMessagePayload {
  playerId: string;
  playerName: string;
  text: string;
  kind: 'chat' | 'guess';
  guessedOnly: boolean;
}

/**
 * Room-wide when `correct` (with points, never the word); private to the
 * guesser when `close` (one letter off).
 */
export interface GuessResultPayload {
  correct: boolean;
  close: boolean;
  playerId: string;
  playerName: string;
  points?: number;
  order?: number;
  text?: string;
}
