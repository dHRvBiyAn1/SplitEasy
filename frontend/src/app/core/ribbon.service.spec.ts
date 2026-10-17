import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { RibbonService } from './ribbon.service';
import { MotionPreferenceService } from './motion-preference.service';
import { SessionService } from './auth/session.service';

describe('Silk Ribbon ownership', () => {
  const ctx = {
    setTransform: vi.fn(),
    clearRect: vi.fn(),
    beginPath: vi.fn(),
    arc: vi.fn(),
    stroke: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
  };
  let raf: ReturnType<typeof vi.fn>;
  function touch(count = 1) {
    const event = new Event('touchstart', { bubbles: true });
    Object.defineProperty(event, 'touches', {
      value: Array.from({ length: count }, () => ({ clientX: 30, clientY: 50 })),
    });
    document.body.dispatchEvent(event);
  }
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal('CanvasRenderingContext2D', function () {});
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      ctx as unknown as CanvasRenderingContext2D,
    );
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockImplementation((q: string) => ({
        matches: q.includes('any-pointer'),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
    raf = vi.fn().mockReturnValue(1);
    vi.stubGlobal('requestAnimationFrame', raf);
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  it('starts a touch decoration and clears it synchronously on identity change', () => {
    const ribbon = TestBed.inject(RibbonService);
    ribbon.init();
    TestBed.tick();
    touch();
    expect(document.querySelector<HTMLCanvasElement>('.silk-ribbon')?.hidden).toBe(false);
    TestBed.inject(SessionService).reset();
    expect(document.querySelector<HTMLCanvasElement>('.silk-ribbon')?.hidden).toBe(true);
  });
  it('stops an active trace and prevents new work when motion is reduced', () => {
    const ribbon = TestBed.inject(RibbonService);
    ribbon.init();
    TestBed.tick();
    touch();
    TestBed.inject(MotionPreferenceService).setReduced(true);
    TestBed.tick();
    const frames = raf.mock.calls.length;
    touch();
    expect(raf).toHaveBeenCalledTimes(frames);
    expect(document.querySelector<HTMLCanvasElement>('.silk-ribbon')?.hidden).toBe(true);
    expect(document.documentElement.hasAttribute('data-ribbon')).toBe(false);
  });
  it('lets pinch gestures proceed without decoration', () => {
    TestBed.inject(RibbonService).init();
    TestBed.tick();
    touch();
    touch(2);
    expect(document.querySelector<HTMLCanvasElement>('.silk-ribbon')?.hidden).toBe(true);
  });
  it('removes the layer and input listeners on teardown', () => {
    TestBed.inject(RibbonService).init();
    TestBed.tick();
    TestBed.resetTestingModule();
    const frames = raf.mock.calls.length;
    touch();
    expect(document.querySelector('.silk-ribbon')).toBeNull();
    expect(raf).toHaveBeenCalledTimes(frames);
  });
});
