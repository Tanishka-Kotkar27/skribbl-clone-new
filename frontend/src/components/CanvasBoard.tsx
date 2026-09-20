import {
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import { canvasStore, type ClientStroke } from '../canvas/canvasStore';
import type { Point } from '../types/events';
import { gameSocket } from '../ws/socket';
import Icon from './Icon';

// Points are sent normalised to 0-1 so they land in the same place on every
// screen size, and batched every ~30ms rather than one message per pointermove.
// The drawer paints locally straight away; undo and clear come back from the
// server so every canvas ends up identical. Stroke data lives in canvasStore.

interface CanvasBoardProps {
  /** True only for the current drawer while the turn is live. */
  canDraw: boolean;
  myId: string;
  /** Overlay content (word choice, round end, game over) shown over the canvas. */
  children?: ReactNode;
}

/** Brush sizes are expressed against this width, then scaled to the real canvas. */
const REFERENCE_WIDTH = 800;
const BACKGROUND = '#ffffff';
const FLUSH_INTERVAL_MS = 30;
/** Ignore pointer jitter smaller than this (as a fraction of the canvas size). */
const MIN_POINT_DISTANCE = 0.0015;

const PALETTE = [
  '#000000', '#4d4d4d', '#9e9e9e', '#ffffff',
  '#e53935', '#fb8c00', '#fdd835', '#43a047',
  '#00acc1', '#1e88e5', '#3949ab', '#8e24aa',
  '#d81b60', '#f48fb1', '#8d6e63', '#5d4037',
];

const SIZES: { value: number; label: string }[] = [
  { value: 3, label: 'S' },
  { value: 8, label: 'M' },
  { value: 16, label: 'L' },
  { value: 32, label: 'XL' },
];

function round4(v: number): number {
  return Math.round(Math.min(1, Math.max(0, v)) * 10_000) / 10_000;
}

export default function CanvasBoard({ canDraw, myId, children }: CanvasBoardProps) {
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  const activeIdRef = useRef<string | null>(null);
  const pendingRef = useRef<Point[]>([]);
  const flushTimerRef = useRef<number | null>(null);
  const counterRef = useRef(0);

  const [color, setColor] = useState('#000000');
  const [size, setSize] = useState(8);
  const [eraser, setEraser] = useState(false);

  // ------------------------------------------------------------ painting

  /** Draws a stroke from point index `from` onward (0 = the whole stroke). */
  function drawStroke(stroke: ClientStroke, from: number) {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx || stroke.points.length === 0) return;

    const w = canvas.width;
    const h = canvas.height;
    const width = Math.max(1, (stroke.size * w) / REFERENCE_WIDTH);
    const paint = stroke.eraser ? BACKGROUND : stroke.color;

    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.lineWidth = width;
    ctx.strokeStyle = paint;
    ctx.fillStyle = paint;

    const pts = stroke.points;
    if (pts.length === 1) {
      // A single click is a dot; a zero-length line would draw nothing.
      ctx.beginPath();
      ctx.arc(pts[0].x * w, pts[0].y * h, width / 2, 0, Math.PI * 2);
      ctx.fill();
      return;
    }
    const start = Math.max(0, from - 1);
    ctx.beginPath();
    ctx.moveTo(pts[start].x * w, pts[start].y * h);
    for (let i = start + 1; i < pts.length; i++) {
      ctx.lineTo(pts[i].x * w, pts[i].y * h);
    }
    ctx.stroke();
  }

  /** Full redraw from the store: after undo, clear, replay, resize or mount. */
  function repaint() {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    ctx.fillStyle = BACKGROUND;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    for (const stroke of canvasStore.all()) {
      drawStroke(stroke, 0);
    }
  }

  // Size the backing store to the on-screen size and pixel density so lines
  // stay crisp, paint what the store already holds (this is how a late mount
  // catches up), then follow the store's changes.
  useEffect(() => {
    const wrap = wrapRef.current;
    const canvas = canvasRef.current;
    if (!wrap || !canvas) return;

    const resize = () => {
      const dpr = window.devicePixelRatio || 1;
      const cssWidth = wrap.clientWidth;
      canvas.width = Math.round(cssWidth * dpr);
      canvas.height = Math.round(cssWidth * 0.75 * dpr);
      repaint();
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(wrap);

    const unsubscribe = canvasStore.subscribe((change) => {
      if (change.kind === 'reset') {
        activeIdRef.current = null;
        pendingRef.current = [];
        repaint();
      } else {
        drawStroke(change.stroke, change.from);
      }
    });

    return () => {
      observer.disconnect();
      unsubscribe();
    };
    // drawStroke and repaint only read refs and the store; mount-once is intended.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ------------------------------------------------------- local drawing

  function toPoint(e: { clientX: number; clientY: number }): Point {
    const rect = canvasRef.current!.getBoundingClientRect();
    return {
      x: round4((e.clientX - rect.left) / rect.width),
      y: round4((e.clientY - rect.top) / rect.height),
    };
  }

  function flush() {
    if (flushTimerRef.current !== null) {
      window.clearTimeout(flushTimerRef.current);
      flushTimerRef.current = null;
    }
    const id = activeIdRef.current;
    if (!id || pendingRef.current.length === 0) return;
    gameSocket.send('draw-move', { strokeId: id, points: pendingRef.current });
    pendingRef.current = [];
  }

  function scheduleFlush() {
    if (flushTimerRef.current === null) {
      flushTimerRef.current = window.setTimeout(flush, FLUSH_INTERVAL_MS);
    }
  }

  function finishStroke() {
    const id = activeIdRef.current;
    if (!id) return;
    flush();
    gameSocket.send('draw-end', { strokeId: id });
    activeIdRef.current = null;
  }

  function handlePointerDown(e: ReactPointerEvent<HTMLCanvasElement>) {
    if (!canDraw || e.button !== 0) return;
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);

    counterRef.current += 1;
    const id = `${myId.slice(0, 8)}-${Date.now().toString(36)}-${counterRef.current}`;
    const point = toPoint(e);

    activeIdRef.current = id;
    pendingRef.current = [];
    canvasStore.startLocal({ id, color, size, eraser, points: [point] });
    gameSocket.send('draw-start', { strokeId: id, x: point.x, y: point.y, color, size, eraser });
  }

  function handlePointerMove(e: ReactPointerEvent<HTMLCanvasElement>) {
    const id = activeIdRef.current;
    const stroke = id ? canvasStore.get(id) : undefined;
    if (!id || !stroke) return;

    // Coalesced events recover positions the browser merged into one
    // pointermove, so fast curves stay smooth instead of going angular.
    const native = e.nativeEvent;
    const samples = typeof native.getCoalescedEvents === 'function' ? native.getCoalescedEvents() : [];
    const events = samples.length > 0 ? samples : [native];

    let last = stroke.points[stroke.points.length - 1];
    const fresh: Point[] = [];
    for (const sample of events) {
      const point = toPoint(sample);
      if (Math.hypot(point.x - last.x, point.y - last.y) < MIN_POINT_DISTANCE) continue;
      fresh.push(point);
      last = point;
    }
    if (fresh.length === 0) return;

    canvasStore.extendLocal(id, fresh);
    pendingRef.current.push(...fresh);
    scheduleFlush();
  }

  // If the turn ends while the pen is down, close the stroke cleanly.
  useEffect(() => {
    if (!canDraw) finishStroke();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canDraw]);

  // Ctrl/Cmd+Z to undo while drawing.
  useEffect(() => {
    if (!canDraw) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        gameSocket.send('undo');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [canDraw]);

  // ------------------------------------------------------------- render

  return (
    <div className="board">
      <div className="canvas-wrap" ref={wrapRef}>
        <canvas
          ref={canvasRef}
          className={canDraw ? 'drawable' : ''}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={finishStroke}
          onPointerCancel={finishStroke}
          onLostPointerCapture={finishStroke}
        />
        {children && <div className="canvas-overlay">{children}</div>}
      </div>

      {canDraw && (
        <div className="toolbar">
          <div className="swatches">
            {PALETTE.map((c) => (
              <button
                key={c}
                type="button"
                className={`swatch ${!eraser && color === c ? 'active' : ''}`}
                style={{ background: c }}
                title={c}
                aria-label={`Colour ${c}`}
                onClick={() => {
                  setColor(c);
                  setEraser(false);
                }}
              />
            ))}
          </div>
          <span className="toolbar-sep" />
          <div className="tool-group">
            {SIZES.map((s) => (
              <button
                key={s.value}
                type="button"
                className={`tool ${size === s.value ? 'active' : ''}`}
                title={`Brush size ${s.label}`}
                aria-label={`Brush size ${s.label}`}
                onClick={() => setSize(s.value)}
              >
                <span className="size-dot" style={{ width: 3 + s.value / 2, height: 3 + s.value / 2 }} />
              </button>
            ))}
          </div>
          <span className="toolbar-sep" />
          <div className="tool-group">
            <button
              type="button"
              className={`tool ${eraser ? 'active' : ''}`}
              title="Eraser"
              aria-label="Eraser"
              aria-pressed={eraser}
              onClick={() => setEraser((v) => !v)}
            >
              <Icon name="eraser" />
            </button>
            <button
              type="button"
              className="tool"
              title="Undo (Ctrl+Z)"
              aria-label="Undo"
              onClick={() => gameSocket.send('undo')}
            >
              <Icon name="undo" />
            </button>
            <button
              type="button"
              className="tool danger"
              title="Clear canvas"
              aria-label="Clear canvas"
              onClick={() => gameSocket.send('clear')}
            >
              <Icon name="trash" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
