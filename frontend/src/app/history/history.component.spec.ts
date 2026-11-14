import { describe, expect, it, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { HistoryComponent } from './history.component';
import { HistoryItem } from './history.models';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';

function item(id: string, overrides: Partial<HistoryItem> = {}): HistoryItem {
  return {
    type: 'EXPENSE',
    id,
    groupId: 'group-1',
    groupName: 'Trip',
    financialDate: '2026-03-01',
    description: `Entry ${id}`,
    currency: 'USD',
    amountMinor: 1200,
    updatedAt: '2026-03-01T10:00:00Z',
    ...overrides,
  };
}

describe('HistoryComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: 'group-1' })) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession({ id: 'alice', email: 'alice@example.com', displayName: 'Alice' });
    vi.spyOn(TestBed.inject(AuthService), 'isAuthenticated').mockReturnValue(true);
  });

  function render() {
    const fixture = TestBed.createComponent(HistoryComponent);
    fixture.detectChanges();
    return fixture;
  }

  function pending(query: string) {
    return http.expectOne((r) => r.urlWithParams === `/api/groups/history?${query}`);
  }

  it('loads the first page and appends the next one with the cursor', async () => {
    const fixture = render();
    pending('groupId=group-1').flush({ items: [item('a'), item('b')], nextCursor: 'c1' });
    await fixture.whenStable();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('li.entry')).toHaveLength(2);

    (
      Array.from(el.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Load more'),
      ) as HTMLButtonElement
    ).click();
    pending('groupId=group-1&cursor=c1').flush({ items: [item('c')], nextCursor: null });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelectorAll('li.entry')).toHaveLength(3);
    expect(el.textContent).not.toContain('Load more');
  });

  it('applies date bounds and restarts from the first page', async () => {
    const fixture = render();
    pending('groupId=group-1').flush({ items: [item('a')], nextCursor: 'c1' });
    const component = fixture.componentInstance as unknown as {
      from: string;
      to: string;
      applyFilter(): void;
    };
    component.from = '2026-03-01';
    component.to = '2026-03-31';
    component.applyFilter();
    pending('groupId=group-1&from=2026-03-01&to=2026-03-31').flush({
      items: [item('z')],
      nextCursor: null,
    });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('li.entry')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('Entry z');
  });

  it('keeps loaded rows when load more fails and retries the same cursor', async () => {
    const fixture = render();
    pending('groupId=group-1').flush({ items: [item('a')], nextCursor: 'c1' });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    (
      Array.from(el.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Load more'),
      ) as HTMLButtonElement
    ).click();
    pending('groupId=group-1&cursor=c1').flush('x', { status: 503, statusText: 'down' });
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Could not load more');
    expect(el.querySelectorAll('li.entry')).toHaveLength(1);

    (
      Array.from(el.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Try again'),
      ) as HTMLButtonElement
    ).click();
    pending('groupId=group-1&cursor=c1').flush({ items: [item('b')], nextCursor: null });
    fixture.detectChanges();
    expect(el.querySelectorAll('li.entry')).toHaveLength(2);
  });

  it('shows a retry when the first page fails', async () => {
    const fixture = render();
    pending('groupId=group-1').flush('x', { status: 500, statusText: 'err' });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Could not load history');
    (
      Array.from(el.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Try again'),
      ) as HTMLButtonElement
    ).click();
    pending('groupId=group-1').flush({ items: [], nextCursor: null });
    fixture.detectChanges();
    expect(el.textContent).toContain('No history in this range.');
  });

  it('exports the CSV with the applied filter and reports a failure', async () => {
    const fixture = render();
    pending('groupId=group-1').flush({ items: [], nextCursor: null });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const component = fixture.componentInstance as unknown as {
      from: string;
      applyFilter(): void;
      download(): void;
    };
    component.from = '2026-03-01';
    component.applyFilter();
    pending('groupId=group-1&from=2026-03-01').flush({ items: [], nextCursor: null });

    component.download();
    const csv = http.expectOne(
      (r) => r.urlWithParams === '/api/groups/history/export?groupId=group-1&from=2026-03-01',
    );
    expect(csv.request.responseType).toBe('blob');
    csv.flush(new Blob(['a,b']));
    expect(click).toHaveBeenCalledOnce();

    component.download();
    http
      .expectOne((r) => r.url.startsWith('/api/groups/history/export'))
      .flush(new Blob(['no']), { status: 403, statusText: 'no' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Could not export history');
  });
});
