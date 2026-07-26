import { avatarTint, groupGlyphTint } from './avatar';

describe('avatarTint', () => {
  it('is deterministic per key', () => {
    expect(avatarTint('user-1')).toEqual(avatarTint('user-1'));
  });

  it('gives different keys different colors', () => {
    const keys = ['a', 'b', 'c', 'd', 'e'].map((k) => avatarTint(k).background);
    expect(new Set(keys).size).toBeGreaterThan(1);
  });

  it('falls back to a neutral tint for a missing key', () => {
    expect(avatarTint(null).background).toContain('#8FA096');
  });
});

describe('groupGlyphTint', () => {
  it('is deterministic per group name', () => {
    expect(groupGlyphTint('Lisbon Trip')).toEqual(groupGlyphTint('Lisbon Trip'));
  });

  it('gives different groups different hues', () => {
    expect(groupGlyphTint('Lisbon Trip').background).not.toBe(groupGlyphTint('Dinner Club').background);
  });
});

// Regression guard: initials used to be tuned for dark mode only (mixed toward white on a
// hardcoded dark tile), which left them unreadable/odd on light surfaces. Both helpers must
// stay theme-relative — background over --surface, lettering shifted toward --avatar-ink.
describe('theme awareness', () => {
  for (const [name, style] of [
    ['avatarTint', avatarTint('user-1')],
    ['groupGlyphTint', groupGlyphTint('Lisbon Trip')],
  ] as const) {
    it(`${name} mixes its background over --surface`, () => {
      expect(style.background).toContain('var(--surface)');
    });

    it(`${name} shifts its lettering toward --avatar-ink`, () => {
      expect(style.color).toContain('var(--avatar-ink)');
      expect(style.color).not.toContain('white');
    });
  }
});
