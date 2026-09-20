/**
 * Initials on a colour chosen from the name, so each player is recognisable at
 * a glance and keeps the same colour for the whole game.
 */
const TONES = [
  { bg: '#fde3d8', fg: '#8a2c0d' },
  { bg: '#dcebfb', fg: '#1d4f86' },
  { bg: '#e2f2dd', fg: '#2f6a22' },
  { bg: '#f2e3f7', fg: '#6b2a80' },
  { bg: '#fff0c7', fg: '#7a5300' },
  { bg: '#d9f1ef', fg: '#1d625b' },
  { bg: '#e9e5fb', fg: '#44358f' },
  { bg: '#fbdfe6', fg: '#8c1d3a' },
];

function hash(text: string): number {
  let h = 0;
  for (let i = 0; i < text.length; i++) {
    h = (h * 31 + text.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

interface AvatarProps {
  name: string;
  /** Stable key for the colour; the player id, so renames keep the colour. */
  seed: string;
  size?: number;
}

export default function Avatar({ name, seed, size = 32 }: AvatarProps) {
  const tone = TONES[hash(seed) % TONES.length];
  return (
    <span
      className="avatar"
      style={{ width: size, height: size, background: tone.bg, color: tone.fg, fontSize: size * 0.38 }}
      aria-hidden="true"
    >
      {initials(name)}
    </span>
  );
}
