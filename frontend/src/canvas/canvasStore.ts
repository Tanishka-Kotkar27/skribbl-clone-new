import {
  EventType,
  type CanvasReplayPayload,
  type DrawDataPayload,
  type DrawUndoPayload,
  type Point,
} from '../types/events';
import { gameSocket, type GameSocket } from '../ws/socket';

export interface ClientStroke {
  id: string;
  color: string;
  size: number;
  eraser: boolean;
  points: Point[];
}

/** What changed, so the view can do the cheapest correct redraw. */
export type CanvasChange =
  /** New points on one stroke: draw from index `from` onward. */
  | { kind: 'segment'; stroke: ClientStroke; from: number }
  /** Anything else (undo, clear, replay, new turn): repaint everything. */
  | { kind: 'reset' };

/**
 * The drawing's state, held outside React.
 *
 * Why not inside the canvas component: a player who joins or refreshes mid-turn
 * is sent the whole drawing as one `canvas_replay` message, which can arrive
 * before React has mounted the canvas. A component that subscribes on mount
 * would miss it and show a blank board. This store subscribes once, when the
 * app loads — before any socket connects — so no stroke message is ever missed,
 * and the canvas simply paints whatever the store holds whenever it mounts.
 *
 * It also avoids React state for strokes: a stroke can add hundreds of points,
 * and re-rendering for each would make drawing lag.
 */
export class CanvasStore {
  private strokes = new Map<string, ClientStroke>();
  /** Strokes this browser drew; their server echo is skipped (already on screen). */
  private own = new Set<string>();
  private listeners = new Set<(change: CanvasChange) => void>();

  constructor(socket: GameSocket) {
    socket.on<DrawDataPayload>(EventType.DRAW_DATA, (data) => this.applyRemote(data));

    socket.on<DrawUndoPayload>(EventType.DRAW_UNDO, ({ strokeId }) => {
      this.strokes.delete(strokeId);
      this.own.delete(strokeId);
      this.emit({ kind: 'reset' });
    });

    socket.on(EventType.CANVAS_CLEAR, () => this.reset());

    socket.on<CanvasReplayPayload>(EventType.CANVAS_REPLAY, ({ strokes }) => {
      this.strokes = new Map(
        strokes.map((s) => [
          s.id,
          { id: s.id, color: s.color, size: s.size, eraser: s.eraser, points: [...s.points] },
        ]),
      );
      this.own.clear();
      this.emit({ kind: 'reset' });
    });

    // Every new turn starts on a blank board.
    socket.on(EventType.ROUND_START, () => this.reset());
  }

  /** Strokes in drawing order (Map preserves insertion order). */
  all(): Iterable<ClientStroke> {
    return this.strokes.values();
  }

  get(id: string): ClientStroke | undefined {
    return this.strokes.get(id);
  }

  subscribe(listener: (change: CanvasChange) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /** Empties the board, e.g. on joining a room or a new turn. */
  reset(): void {
    this.strokes.clear();
    this.own.clear();
    this.emit({ kind: 'reset' });
  }

  /** The local drawer started a stroke. */
  startLocal(stroke: ClientStroke): void {
    this.strokes.set(stroke.id, stroke);
    this.own.add(stroke.id);
    this.emit({ kind: 'segment', stroke, from: 0 });
  }

  /** The local drawer extended a stroke. */
  extendLocal(id: string, points: Point[]): void {
    const stroke = this.strokes.get(id);
    if (!stroke || points.length === 0) return;
    const from = stroke.points.length;
    stroke.points.push(...points);
    this.emit({ kind: 'segment', stroke, from });
  }

  private applyRemote(data: DrawDataPayload): void {
    if (this.own.has(data.strokeId)) return;

    if (data.kind === 'start') {
      const stroke: ClientStroke = {
        id: data.strokeId,
        color: data.color ?? '#000000',
        size: data.size ?? 8,
        eraser: data.eraser ?? false,
        points: [...(data.points ?? [])],
      };
      this.strokes.set(stroke.id, stroke);
      this.emit({ kind: 'segment', stroke, from: 0 });
    } else if (data.kind === 'move' && data.points) {
      const stroke = this.strokes.get(data.strokeId);
      if (!stroke) return;
      const from = stroke.points.length;
      stroke.points.push(...data.points);
      this.emit({ kind: 'segment', stroke, from });
    }
    // 'end' needs no drawing: the stroke is already complete on screen.
  }

  private emit(change: CanvasChange): void {
    for (const listener of this.listeners) listener(change);
  }
}

/** One store for the app's one socket. */
export const canvasStore = new CanvasStore(gameSocket);
