/**
 * Product mark and wordmark. The tile matches the favicon in index.html: the
 * brand colour with a single hand-drawn stroke.
 */
export const APP_NAME = 'Inkling';

export default function Logo({ size = 30, showText = true }: { size?: number; showText?: boolean }) {
  return (
    <span className="logo">
      <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" className="icon">
        <rect width="32" height="32" rx="9" fill="var(--brand)" />
        <path
          d="M8 20.5c2.2-5.6 4.6-8.4 6.3-7.2 1.9 1.3-1.6 6.8.4 7.6 2 .8 3.9-6.6 6.4-6.1 1.6.3 1.3 3 2.9 3.2"
          fill="none"
          stroke="#fff"
          strokeWidth="2.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      {showText && <span className="logo-text">{APP_NAME}</span>}
    </span>
  );
}
