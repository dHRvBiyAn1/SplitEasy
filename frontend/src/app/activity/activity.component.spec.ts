import { describe, expect, it, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ActivityComponent } from './activity.component';
import { ActivityItem } from './activity.models';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';

function event(id: string, overrides: Partial<ActivityItem> = {}): ActivityItem {
  return {
    id,
    seq: 1,
    groupId: 'g1',
    groupName: 'Trip',
    kind: 'EXPENSE_CREATED',
    subjectId: 'e1',
    actor: { id: 'u1', displayName: 'Alice' },
    summary: { description: `Dinner ${id}` },
    occurredAt: '2026-03-01T10:00:00Z',
    ...overrides,
  };
}

describe('ActivityComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession({ id: 'alice', email: 'alice@example.com', displayName: 'Alice' });
    vi.spyOn(TestBed.inject(AuthService), 'isAuthenticated').mockReturnValue(true);
  });

  function render() {
    const fixture = TestBed.createComponent(ActivityComponent);
    fixture.detectChanges();
    http.match((r) => r.url === '/api/dashboard');
    return fixture;
  }

  const feed = (query: string) =>
    http.expectOne((r) => r.urlWithParams === `/api/activity${query}`);

  it('pages with the cursor, filters by group and retries a failed page', async () => {
    const fixture = render();
    feed('').flush({ items: [event('a')], nextCursor: '7' });
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Alice added expense “Dinner a”');

    (el.querySelector('button.dc-btn-outline') as HTMLButtonElement).click();
    feed('?cursor=7').flush('x', { status: 500, statusText: 'err' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('Could not load more activity');
    expect(el.textContent).toContain('Dinner a');

    (el.querySelector('[role=alert] button') as HTMLButtonElement).click();
    feed('?cursor=7').flush({
      items: [event('b', { kind: 'EXPENSE_DELETED' })],
      nextCursor: null,
    });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('Alice deleted expense “Dinner b”');
    expect(el.textContent).toContain('activity starts from when it was introduced');

    const select = el.querySelector('select') as HTMLSelectElement;
    select.innerHTML = '<option value="g1">Trip</option>';
    select.value = 'g1';
    select.dispatchEvent(new Event('change'));
    feed('?groupId=g1').flush({ items: [], nextCursor: null });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('No activity yet.');
  });
});
