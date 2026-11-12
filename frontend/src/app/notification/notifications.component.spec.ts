import { describe, expect, it, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { NotificationsComponent } from './notifications.component';
import { NotificationService } from './notification.service';
import { NotificationPreferencesComponent } from '../profile/notification-preferences.component';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';

const note = (id: string, readAt: string | null) => ({
  id,
  readAt,
  createdAt: '2026-03-01T10:00:00Z',
  event: {
    id: `e-${id}`,
    seq: 1,
    groupId: 'g1',
    groupName: 'Trip',
    kind: 'MEMBER_ADDED',
    subjectId: 'g1',
    actor: { id: 'u1', displayName: 'Alice' },
    summary: { member: { displayName: 'Bob' } },
    occurredAt: '2026-03-01T10:00:00Z',
  },
});

describe('notifications', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession({ id: 'alice', email: 'alice@example.com', displayName: 'Alice' });
    vi.spyOn(TestBed.inject(AuthService), 'isAuthenticated').mockReturnValue(true);
  });

  it('marks one and then all as read and keeps the bell count in step', async () => {
    const service = TestBed.inject(NotificationService);
    service.unread.set(2);
    const fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    http
      .expectOne('/api/notifications')
      .flush({ items: [note('n1', null), note('n2', null)], nextCursor: null });
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Alice added Bob to Trip');

    (el.querySelector('li button') as HTMLButtonElement).click();
    http.expectOne('/api/notifications/n1/read').flush(null);
    http.expectOne('/api/notifications/unread-count').flush({ count: 1 });
    fixture.detectChanges();
    expect(service.unread()).toBe(1);
    expect(el.querySelectorAll('li.unread').length).toBe(1);

    (el.querySelector('.actions button') as HTMLButtonElement).click();
    http.expectOne('/api/notifications/read-all').flush(null);
    fixture.detectChanges();
    expect(service.unread()).toBe(0);
    expect(el.querySelectorAll('li.unread').length).toBe(0);
  });

  it('ignores an unread count that was requested before mark-all succeeded', () => {
    const service = TestBed.inject(NotificationService);
    service.unread.set(2);
    service.revalidate();
    const late = http.expectOne('/api/notifications/unread-count');
    service.markAllRead().subscribe();
    http.expectOne('/api/notifications/read-all').flush(null);
    expect(service.unread()).toBe(0);
    // The older read is cancelled, so its late answer can never reach the badge.
    expect(late.cancelled).toBe(true);
    expect(service.unread()).toBe(0);
  });

  it('ignores an unread count that was requested before mark-read succeeded', () => {
    const service = TestBed.inject(NotificationService);
    service.revalidate();
    const stale = http.expectOne('/api/notifications/unread-count');
    service.markRead('n1').subscribe();
    http.expectOne('/api/notifications/n1/read').flush(null);
    expect(stale.cancelled).toBe(true);
    http.expectOne('/api/notifications/unread-count').flush({ count: 1 });
    expect(service.unread()).toBe(1);
  });

  it('shows a failed mark-read without changing the row', async () => {
    const fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    http.expectOne('/api/notifications').flush({ items: [note('n1', null)], nextCursor: null });
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('li button') as HTMLButtonElement).click();
    http.expectOne('/api/notifications/n1/read').flush('x', { status: 500, statusText: 'err' });
    fixture.detectChanges();
    expect(el.textContent).toContain('Could not mark that notification as read');
    expect(el.querySelectorAll('li.unread').length).toBe(1);
  });

  it('saves a preference and restores the switch when saving fails', async () => {
    const fixture = TestBed.createComponent(NotificationPreferencesComponent);
    fixture.detectChanges();
    http.expectNone('/api/me/notification-preferences');
    const details = fixture.nativeElement.querySelector('details') as HTMLDetailsElement;
    details.open = true;
    details.dispatchEvent(new Event('toggle'));
    http
      .expectOne('/api/me/notification-preferences')
      .flush({ expenses: true, payments: true, groupChanges: true });
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const boxes = el.querySelectorAll<HTMLInputElement>('input[type=checkbox]');
    boxes[0].click();
    const save = http.expectOne('/api/me/notification-preferences');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ expenses: false, payments: true, groupChanges: true });
    save.flush({ expenses: false, payments: true, groupChanges: true });
    fixture.detectChanges();
    expect(boxes[0].checked).toBe(false);

    boxes[1].click();
    http
      .expectOne('/api/me/notification-preferences')
      .flush('x', { status: 500, statusText: 'err' });
    fixture.detectChanges();
    expect(boxes[1].checked).toBe(true);
    expect(el.textContent).toContain('It was not changed');
  });
});
