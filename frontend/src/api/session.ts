import type { JoinRoomResponse } from '../types/game';

/**
 * Remembers who this browser tab is, so a refresh mid-game can rejoin the same
 * seat rather than appearing as a second player.
 *
 * `sessionStorage`, not `localStorage`, is the right choice here: it is scoped
 * per tab, which means two tabs on one machine are two independent players.
 * That is exactly what you need to test a multiplayer game on a single laptop.
 */
const KEY = 'skribbl.session';

export interface StoredSession {
  roomCode: string;
  playerId: string;
  playerName: string;
  host: boolean;
}

export function saveSession(response: JoinRoomResponse): StoredSession {
  const session: StoredSession = {
    roomCode: response.roomCode,
    playerId: response.playerId,
    playerName: response.playerName,
    host: response.host,
  };
  try {
    sessionStorage.setItem(KEY, JSON.stringify(session));
  } catch {
    // Private browsing can refuse storage; the app still works for this tab.
  }
  return session;
}

export function loadSession(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as StoredSession) : null;
  } catch {
    return null;
  }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // ignore
  }
}
