/** Shapes returned by the REST API and carried in `room_state` events. */

export type RoomStatus = 'WAITING' | 'IN_PROGRESS' | 'FINISHED' | 'ABANDONED';

export type GamePhase = 'LOBBY' | 'CHOOSING' | 'DRAWING' | 'ROUND_END' | 'GAME_OVER';

export type WordMode = 'NORMAL' | 'HIDDEN' | 'COMBINATION';

export interface GameSettings {
  maxPlayers: number;
  rounds: number;
  drawTimeSeconds: number;
  wordChoices: number;
  hints: number;
  wordMode: WordMode;
  private: boolean;
  customWords: string[];
}

export interface PlayerView {
  id: string;
  name: string;
  score: number;
  roundScore: number;
  host: boolean;
  connected: boolean;
  guessedCurrentRound: boolean;
}

export interface RoomState {
  code: string;
  hostId: string | null;
  status: RoomStatus;
  phase: GamePhase;
  settings: GameSettings;
  players: PlayerView[];
  currentRound: number;
  totalRounds: number;
  drawerId: string | null;
  maskedWord: string;
  remainingSeconds: number;
}

export interface JoinRoomResponse {
  roomCode: string;
  playerId: string;
  playerName: string;
  host: boolean;
  joinUrl: string;
  room: RoomState;
}

export interface ApiError {
  error: string;
  message: string;
  fields: Record<string, string>;
}

/**
 * Settings bounds, mirrored from `GameSettings.java`. Kept here so the form can
 * clamp inputs client-side; the server validates them again regardless, because
 * a client-side bound is a convenience, never a control.
 */
export const SETTINGS_BOUNDS = {
  maxPlayers: { min: 2, max: 20 },
  rounds: { min: 2, max: 10 },
  drawTimeSeconds: { min: 15, max: 240 },
  wordChoices: { min: 1, max: 5 },
  hints: { min: 0, max: 5 },
} as const;

export const DEFAULT_SETTINGS: GameSettings = {
  maxPlayers: 8,
  rounds: 3,
  drawTimeSeconds: 80,
  wordChoices: 3,
  hints: 2,
  wordMode: 'NORMAL',
  private: true,
  customWords: [],
};
