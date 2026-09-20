/**
 * A small stroke-icon set, drawn on a 24px grid with round caps so every icon
 * shares one visual weight. Replaces emoji, which render differently on every
 * OS and read as placeholder UI.
 */

type Shape =
  | { d: string }
  | { circle: [number, number, number] }
  | { rect: [number, number, number, number, number] };

const ICONS = {
  pencil: [{ d: 'M4 20h4L18.5 9.5a2.12 2.12 0 0 0-3-3L5 17v3Z' }, { d: 'M14.5 7.5l3 3' }],
  check: [{ d: 'M5 12.5l4.5 4.5L19 7.5' }],
  crown: [{ d: 'M5 18h14' }, { d: 'M5 15 3.5 7.5 8.5 11 12 5l3.5 6 5-3.5L19 15Z' }],
  eraser: [
    { d: 'M8 20h12' },
    { d: 'M4.6 13.4l8-8a2 2 0 0 1 2.8 0l3.2 3.2a2 2 0 0 1 0 2.8L11 19H7.4l-2.8-2.8a2 2 0 0 1 0-2.8Z' },
    { d: 'M9 9l6 6' },
  ],
  undo: [{ d: 'M9 14 4 9l5-5' }, { d: 'M4 9h10.5a5.5 5.5 0 0 1 0 11H11' }],
  trash: [
    { d: 'M4 7h16' },
    { d: 'M10 11v6M14 11v6' },
    { d: 'M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12' },
    { d: 'M9 7V4h6v3' },
  ],
  copy: [{ rect: [9, 9, 11, 11, 2] }, { d: 'M5 15V6a1 1 0 0 1 1-1h9' }],
  logout: [
    { d: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3' },
    { d: 'M10 17l5-5-5-5' },
    { d: 'M15 12H3' },
  ],
  clock: [{ circle: [12, 12, 9] }, { d: 'M12 7v5l3 2' }],
  users: [
    { circle: [9, 8, 3.5] },
    { d: 'M2.5 20a6.5 6.5 0 0 1 13 0' },
    { d: 'M16 4.6a3.5 3.5 0 0 1 0 6.8' },
    { d: 'M18.5 14.6A6.5 6.5 0 0 1 21.5 20' },
  ],
  trophy: [
    { d: 'M8 4h8v5a4 4 0 0 1-8 0Z' },
    { d: 'M8 6H4.5v1A3.5 3.5 0 0 0 8 10.5' },
    { d: 'M16 6h3.5v1A3.5 3.5 0 0 1 16 10.5' },
    { d: 'M12 13v4' },
    { d: 'M8 20h8' },
  ],
  x: [{ d: 'M6 6l12 12M18 6 6 18' }],
  plus: [{ d: 'M12 5v14M5 12h14' }],
  minus: [{ d: 'M5 12h14' }],
  send: [{ d: 'M4 12 20 4l-6 16-2.5-6.5Z' }, { d: 'M11.5 13.5 20 4' }],
  link: [
    { d: 'M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1' },
    { d: 'M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1' },
  ],
  play: [{ d: 'M8 5.5v13l10.5-6.5Z' }],
  lock: [{ rect: [5, 11, 14, 10, 2] }, { d: 'M8 11V8a4 4 0 0 1 8 0v3' }],
  globe: [{ circle: [12, 12, 9] }, { d: 'M3 12h18' }, { d: 'M12 3a14 14 0 0 1 0 18 14 14 0 0 1 0-18Z' }],
  'wifi-off': [
    { d: 'M3 3l18 18' },
    { d: 'M8.5 16.4a5 5 0 0 1 7 0' },
    { d: 'M5 12.9a10 10 0 0 1 4.4-2.5' },
    { d: 'M14.5 10.4A10 10 0 0 1 19 12.9' },
    { d: 'M2 8.8a15 15 0 0 1 4.2-2.6' },
    { d: 'M10.7 5.1A15 15 0 0 1 22 8.8' },
    { d: 'M12 20h.01' },
  ],
} satisfies Record<string, Shape[]>;

export type IconName = keyof typeof ICONS;

interface IconProps {
  name: IconName;
  size?: number;
  strokeWidth?: number;
  className?: string;
}

export default function Icon({ name, size = 18, strokeWidth = 2, className = 'icon' }: IconProps) {
  const shapes: Shape[] = ICONS[name];
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {shapes.map((shape, i) => {
        if ('d' in shape) return <path key={i} d={shape.d} />;
        if ('circle' in shape) {
          const [cx, cy, r] = shape.circle;
          return <circle key={i} cx={cx} cy={cy} r={r} />;
        }
        const [x, y, w, h, rx] = shape.rect;
        return <rect key={i} x={x} y={y} width={w} height={h} rx={rx} />;
      })}
    </svg>
  );
}
