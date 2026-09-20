import type {
  ApiError,
  GameSettings,
  JoinRoomResponse,
  RoomState,
} from '../types/game';

const BASE_URL =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080';

/**
 * Error carrying the server's structured body, so a form can show
 * "rounds must be at most 10" next to the right control instead of a generic
 * failure message.
 */
export class HttpError extends Error {
  readonly status: number;
  readonly body: ApiError | null;

  constructor(status: number, body: ApiError | null, message: string) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
    this.body = body;
  }

  /** Per-field validation messages, keyed as the server named them. */
  get fieldErrors(): Record<string, string> {
    return this.body?.fields ?? {};
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      headers: { 'Content-Type': 'application/json' },
      ...init,
    });
  } catch {
    // fetch only rejects on network-level failure, which here almost always
    // means the backend is not running or CORS blocked the request.
    throw new HttpError(0, null, 'Cannot reach the server. Is the backend running?');
  }

  if (!response.ok) {
    let body: ApiError | null = null;
    try {
      body = (await response.json()) as ApiError;
    } catch {
      body = null;
    }
    throw new HttpError(
      response.status,
      body,
      body?.message ?? `Request failed with status ${response.status}`,
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export const api = {
  health(): Promise<{ status: string; activeRooms: number; activeSockets: number }> {
    return request('/api/health');
  },

  createRoom(hostName: string, settings: GameSettings): Promise<JoinRoomResponse> {
    return request('/api/rooms', {
      method: 'POST',
      body: JSON.stringify({ hostName, settings }),
    });
  },

  joinRoom(code: string, playerName: string): Promise<JoinRoomResponse> {
    return request(`/api/rooms/${code.toUpperCase()}/join`, {
      method: 'POST',
      body: JSON.stringify({ playerName }),
    });
  },

  getRoom(code: string): Promise<RoomState> {
    return request(`/api/rooms/${code.toUpperCase()}`);
  },

  /** Host only. Everything after this arrives over the WebSocket. */
  startGame(code: string, playerId: string): Promise<RoomState> {
    return request(`/api/rooms/${code.toUpperCase()}/start`, {
      method: 'POST',
      body: JSON.stringify({ playerId }),
    });
  },

  /** Leave on purpose, skipping the reconnect grace period. */
  leaveRoom(code: string, playerId: string): Promise<void> {
    return request(`/api/rooms/${code.toUpperCase()}/leave`, {
      method: 'POST',
      body: JSON.stringify({ playerId }),
    });
  },
};
