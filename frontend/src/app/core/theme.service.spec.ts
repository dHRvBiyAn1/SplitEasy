import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('Pocket Objects appearance', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-palette');
    document.documentElement.style.removeProperty('color-scheme');
  });

  it('uses Seafoam & Petrol for a fresh browser', () => {
    TestBed.inject(ThemeService);
    TestBed.tick();
    expect(document.documentElement.dataset['palette']).toBe('seafoam');
  });

  it('matches native controls to an explicit mode', () => {
    const theme = TestBed.inject(ThemeService);
    theme.set('dark');
    TestBed.tick();
    expect(document.documentElement.style.colorScheme).toBe('dark');
    theme.set('light');
    TestBed.tick();
    expect(document.documentElement.style.colorScheme).toBe('light');
  });
});

describe('independent appearance choices', () => {
  beforeEach(() => localStorage.clear());
  it('retains mode when changing the color theme and persists both independently', () => {
    const theme = TestBed.inject(ThemeService);
    theme.set('dark');
    theme.setPalette('peach');
    TestBed.tick();
    expect(theme.choice()).toBe('dark');
    expect(document.documentElement.dataset['palette']).toBe('peach');
    expect(localStorage.getItem('spliteasy.theme')).toBe('dark');
    expect(localStorage.getItem('spliteasy.palette')).toBe('peach');
    theme.set('light');
    TestBed.tick();
    expect(theme.palette()).toBe('peach');
  });
  it('ignores invalid stored palette and mode values', () => {
    localStorage.setItem('spliteasy.palette', 'invalid');
    localStorage.setItem('spliteasy.theme', 'invalid');
    const theme = TestBed.inject(ThemeService);
    expect(theme.palette()).toBe('seafoam');
    expect(theme.choice()).toBe('system');
  });
});
