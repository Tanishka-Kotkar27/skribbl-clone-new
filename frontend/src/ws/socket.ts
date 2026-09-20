import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import type { EventName, ServerEvent } from '../types/events';

/**
 * Thin wrapper around a STOMP client, with one job: give the React layer a
 * `connect / on / send / disconnect` surface and hide every STOMP detail behind
 * it.
 *
 * Two design decisions worth knowing:
 *
 * 1. **Native WebSocket, not SockJS.** The server registers a SockJS endpoint
 *    as a fallback, but the browser uses the raw socket. SockJS's client is a
 *    CommonJS module that expects Node's `global`, which needs a Vite shim and
 *    produces a confusing runtime error when it is missing. Native WebSocket is
 *    universally supported and removes that whole class of problem.
 *
 * 2. **One subscription per room, one handler map.** Every server push arrives
 *    in the same `ServerEvent` envelope on `/topic/room/{code}`, so components
 *    register by event name against a single subscription rather than opening
 *    one per event type.
 */
export class GameSocket {
  private client: Client | null = null;
  private roomSubscription: StompSubscription | null = null;
  private privateSubscription: StompSubscription | null = null;

  private handlers = new Map<string, Set<(payload: unknown) => void>>();
  private statusHandlers = new Set<(status: SocketStatus) => void>();

  private roomCode: string | null = null;
  private status: SocketStatus = 'disconnected';

  /** Opens the socket and subscribes to the room's topic and private queue. */
  connect(roomCode: string, playerId: string, playerName: string): void {
    this.disconnect();
    this.roomCode = roomCode;

    const client = new Client({
      brokerURL: resolveWsUrl(),
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      // Uncomment when debugging frame-level problems; very noisy otherwise.
      // debug: (msg) => console.log('[stomp]', msg),
      onConnect: () => {
        this.setStatus('connected');

        this.roomSubscription = client.subscribe(
          `/topic/room/${roomCode}`,
          (message: IMessage) => this.dispatch(message),
        );

        // Drawer-only traffic: word choices and the confirmed word. These must
        // never travel on the room topic, or every guesser could read the
        // answer straight out of devtools.
        this.privateSubscription = client.subscribe(
          '/user/queue/private',
          (message: IMessage) => this.dispatch(message),
        );

        this.send('join', { roomCode, playerId, playerName });
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame.headers['message'], frame.body);
        this.setStatus('error');
      },
      onWebSocketError: (event) => {
        console.error('WebSocket error:', event);
        this.setStatus('error');
      },
      onWebSocketClose: () => {
        this.setStatus('disconnected');
      },
    });

    this.setStatus('connecting');
    this.client = client;
    client.activate();
  }

  private dispatch(message: IMessage): void {
    let event: ServerEvent;
    try {
      event = JSON.parse(message.body) as ServerEvent;
    } catch (err) {
      console.error('Unparseable server event', message.body, err);
      return;
    }
    const listeners = this.handlers.get(event.type);
    if (!listeners) return;
    for (const listener of listeners) {
      listener(event.payload);
    }
  }

  /**
   * Registers a handler for one event name.
   *
   * @returns an unsubscribe function, so React effects can clean up properly
   */
  on<T = unknown>(type: EventName, handler: (payload: T) => void): () => void {
    const wrapped = handler as (payload: unknown) => void;
    let listeners = this.handlers.get(type);
    if (!listeners) {
      listeners = new Set();
      this.handlers.set(type, listeners);
    }
    listeners.add(wrapped);
    return () => {
      listeners?.delete(wrapped);
    };
  }

  /** Subscribes to connection-status changes, for the UI indicator. */
  onStatusChange(handler: (status: SocketStatus) => void): () => void {
    this.statusHandlers.add(handler);
    handler(this.status);
    return () => {
      this.statusHandlers.delete(handler);
    };
  }

  /**
   * Publishes to `/app/room/{code}/{action}`.
   *
   * Silently drops messages sent while disconnected rather than throwing: a
   * dropped stroke during a reconnect is far better than an exception that
   * unmounts the canvas mid-round.
   */
  send(action: string, body: unknown = {}): void {
    if (!this.client?.connected || !this.roomCode) {
      return;
    }
    this.client.publish({
      destination: `/app/room/${this.roomCode}/${action}`,
      body: JSON.stringify(body),
    });
  }

  disconnect(): void {
    this.roomSubscription?.unsubscribe();
    this.privateSubscription?.unsubscribe();
    this.roomSubscription = null;
    this.privateSubscription = null;

    if (this.client) {
      void this.client.deactivate();
      this.client = null;
    }
    this.roomCode = null;
    this.setStatus('disconnected');
  }

  isConnected(): boolean {
    return this.client?.connected ?? false;
  }

  getStatus(): SocketStatus {
    return this.status;
  }

  private setStatus(status: SocketStatus): void {
    this.status = status;
    for (const handler of this.statusHandlers) {
      handler(status);
    }
  }
}

export type SocketStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

/**
 * Resolves the broker URL, upgrading to `wss://` whenever the page itself is
 * served over HTTPS. Getting this wrong is the classic "works locally, dead in
 * production" bug, so it is handled here once rather than in configuration.
 */
function resolveWsUrl(): string {
  const configured = import.meta.env.VITE_WS_URL as string | undefined;
  if (configured) {
    if (window.location.protocol === 'https:' && configured.startsWith('ws://')) {
      return configured.replace(/^ws:\/\//, 'wss://');
    }
    return configured;
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}

/** One socket for the whole app; rooms are switched by reconnecting. */
export const gameSocket = new GameSocket();
