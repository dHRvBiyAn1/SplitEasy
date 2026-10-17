import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { MotionPreferenceService } from './motion-preference.service';

describe('motion preferences', () => {
  let changed: (event: { matches: boolean }) => void;
  let remove: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    localStorage.clear();
    remove = vi.fn();
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockReturnValue({
        matches: false,
        addEventListener: (_: string, fn: typeof changed) => (changed = fn),
        removeEventListener: remove,
      }),
    );
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.unstubAllGlobals();
  });
  it('keeps the system reduction authoritative over the device setting', () => {
    const motion = TestBed.inject(MotionPreferenceService);
    changed({ matches: true });
    motion.setReduced(false);
    TestBed.tick();
    expect(motion.reduced()).toBe(true);
    expect(document.documentElement.dataset['motion']).toBe('reduced');
  });
  it('persists a device choice and exposes the effective reduction', () => {
    const motion = TestBed.inject(MotionPreferenceService);
    motion.setReduced(true);
    TestBed.tick();
    expect(motion.reduced()).toBe(true);
    expect(localStorage.getItem('spliteasy.motion')).toBe('reduced');
    motion.setReduced(false);
    TestBed.tick();
    expect(motion.reduced()).toBe(false);
  });
  it('removes its system listener on teardown', () => {
    TestBed.inject(MotionPreferenceService);
    TestBed.resetTestingModule();
    expect(remove).toHaveBeenCalledWith('change', changed);
  });
});
