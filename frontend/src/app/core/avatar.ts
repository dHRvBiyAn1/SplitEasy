// Per-person avatar tint — deterministic color from the design spec's person palette,
// keyed by a stable id/name so the same person is always the same color. Used everywhere
// a person initials-avatar is shown (never the lime accent — that's for selection state).
//
// Both helpers below are theme-aware: the background is mixed into `--surface` and the
// lettering is shifted toward `--avatar-ink` (black on light surfaces, white on dark ones),
// so initials stay legible in either theme instead of being tuned for dark only.

const PALETTE = [
  '#12A57E',
  '#D96D3F',
  '#3F7BD9',
  '#C25B84',
  '#D9A03F',
  '#7A5FD9',
  '#3FADC2',
  '#5FA05F',
];
const FALLBACK = '#8FA096';

/** Returns a `[style]`-bindable object: soft tinted background + legible lettering. */
export function avatarTint(key: string | undefined | null): { background: string; color: string } {
  return tint(pick(key), 22);
}

/**
 * Tile tint for a group's initial, derived from the group name. Same treatment as a person
 * avatar but off a name-derived hue, so every group keeps a stable identity color.
 */
export function groupGlyphTint(name: string | undefined | null): {
  background: string;
  color: string;
} {
  return tint(`hsl(${hash(name) % 360} 45% 45%)`, 20);
}

/** Soft tint of `color` over the current surface, with text shifted toward the theme's ink. */
function tint(color: string, bgPercent: number): { background: string; color: string } {
  return {
    background: `color-mix(in oklab, ${color} ${bgPercent}%, var(--surface))`,
    color: `color-mix(in oklab, ${color}, var(--avatar-ink) 30%)`,
  };
}

function pick(key: string | undefined | null): string {
  if (!key) {
    return FALLBACK;
  }
  return PALETTE[hash(key) % PALETTE.length];
}

function hash(key: string | undefined | null): number {
  let h = 0;
  for (let i = 0; i < (key?.length ?? 0); i++) {
    h = (Math.imul(h, 31) + (key as string).charCodeAt(i)) >>> 0;
  }
  return h;
}
