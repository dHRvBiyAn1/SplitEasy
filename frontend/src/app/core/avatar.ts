// Per-person avatar tint — deterministic color from the design spec's person palette,
// keyed by a stable id/name so the same person is always the same color. Used everywhere
// a person initials-avatar is shown (never the lime accent — that's for selection state).

const PALETTE = ['#12A57E', '#D96D3F', '#3F7BD9', '#C25B84', '#D9A03F', '#7A5FD9', '#3FADC2', '#5FA05F'];
const FALLBACK = '#8FA096';

/** Returns a `[style]`-bindable object: soft tinted background + a legible on-dark text color. */
export function avatarTint(key: string | undefined | null): { background: string; color: string } {
  const c = pick(key);
  return {
    background: `color-mix(in oklab, ${c} 22%, var(--surface))`,
    color: `color-mix(in oklab, ${c}, white 26%)`,
  };
}

function pick(key: string | undefined | null): string {
  if (!key) {
    return FALLBACK;
  }
  let h = 0;
  for (let i = 0; i < key.length; i++) {
    h = (Math.imul(h, 31) + key.charCodeAt(i)) >>> 0;
  }
  return PALETTE[h % PALETTE.length];
}
