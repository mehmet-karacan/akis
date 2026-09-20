/** Minimal inline SVG country flags. Emoji flags don't render as pictures on Windows, so
 *  language options use these instead of relying on platform emoji font support. */
export function FlagIcon({ country, size = 16 }: { country: 'tr' | 'gb'; size?: number }) {
  const style = { display: 'inline-block', borderRadius: 2, flex: 'none' }
  if (country === 'tr') {
    return <svg width={size} height={size * 0.75} viewBox="0 0 30 22.5" style={style} aria-hidden="true">
      <rect width="30" height="22.5" fill="#e30a17" />
      <circle cx="12.5" cy="11.25" r="6" fill="#fff" />
      <circle cx="14" cy="11.25" r="4.8" fill="#e30a17" />
      <polygon fill="#fff" points="18.5,11.25 22.9,12.68 20.24,8.95 20.24,13.55 22.9,9.82" />
    </svg>
  }
  return <svg width={size} height={size * 0.75} viewBox="0 0 60 45" style={style} aria-hidden="true">
    <rect width="60" height="45" fill="#012169" />
    <path d="M0,0 60,45 M60,0 0,45" stroke="#fff" strokeWidth="9" />
    <path d="M0,0 27,20.25 M0,45 27,24.75 M60,0 33,20.25 M60,45 33,24.75" stroke="#c8102e" strokeWidth="3" />
    <path d="M30,0 30,45 M0,22.5 60,22.5" stroke="#fff" strokeWidth="15" />
    <path d="M30,0 30,45 M0,22.5 60,22.5" stroke="#c8102e" strokeWidth="9" />
  </svg>
}
