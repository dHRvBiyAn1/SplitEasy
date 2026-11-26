import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { SearchComponent } from './search.component';

const row = (id: string, groupName: string | null = 'Trip') => ({
  type: 'EXPENSE',
  id,
  groupId: 'g1',
  groupName,
  financialDate: '2026-03-01',
  description: `Lunch ${id}`,
  currency: 'USD',
  amountMinor: 1200,
  updatedAt: '2026-03-01T10:00:00Z',
});

describe('SearchComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession({ id: 'alice', email: 'a@example.com', displayName: 'Alice' });
    vi.spyOn(TestBed.inject(AuthService), 'isAuthenticated').mockReturnValue(true);
  });

  function render() {
    const fixture = TestBed.createComponent(SearchComponent);
    fixture.detectChanges();
    return fixture;
  }
  const type = (el: HTMLElement, sel: string, value: string) => {
    const input = el.querySelector(sel) as HTMLInputElement | HTMLSelectElement;
    input.value = value;
    input.dispatchEvent(new Event(input.tagName === 'SELECT' ? 'change' : 'input'));
  };
  const submit = (fixture: ReturnType<typeof render>) => {
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit'),
    );
  };
  const search = (query: string) =>
    http.expectOne((r) => r.urlWithParams === `/api/search?${query}`);

  it('gates the amount bounds on a chosen currency', () => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    expect((el.querySelector('[name=minAmount]') as HTMLInputElement).disabled).toBe(true);
    type(el, '[name=currency]', 'USD');
    fixture.detectChanges();
    expect((el.querySelector('[name=minAmount]') as HTMLInputElement).disabled).toBe(false);
  });

  it('searches, pages with the cursor, and shows personal rows as Personal', async () => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    type(el, '[name=q]', 'lunch');
    type(el, '[name=currency]', 'USD');
    fixture.detectChanges();
    type(el, '[name=minAmount]', '5.00');
    submit(fixture);
    search('q=lunch&currency=USD&minAmount=500').flush({
      items: [row('a'), row('b', null)],
      nextCursor: 'c1',
    });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('Lunch a');
    expect(el.textContent).toContain('Personal');
    (el.querySelector('[data-test=load-more]') as HTMLButtonElement).click();
    search('q=lunch&currency=USD&minAmount=500&cursor=c1').flush({
      items: [row('c')],
      nextCursor: null,
    });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('Lunch c');
    expect(el.querySelector('[data-test=load-more]')).toBeNull();
  });

  it('shows an empty state and a retry after failure', async () => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    type(el, '[name=q]', 'zzz');
    submit(fixture);
    search('q=zzz').flush({ items: [], nextCursor: null });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('[data-test=empty]')).not.toBeNull();
    submit(fixture);
    search('q=zzz').flush('x', { status: 500, statusText: 'x' });
    await fixture.whenStable();
    fixture.detectChanges();
    (el.querySelector('[data-test=retry]') as HTMLButtonElement).click();
    search('q=zzz').flush({ items: [row('a')], nextCursor: null });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('Lunch a');
  });
});
