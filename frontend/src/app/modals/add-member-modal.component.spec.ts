import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AddMemberModalComponent } from './add-member-modal.component';
import { ModalService } from './modal.service';

describe('add member load feedback', () => {
  it('deliberately retries unavailable roster without losing the entered email', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const modal = TestBed.inject(ModalService);
    modal.openAddMember('a');
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AddMemberModalComponent);
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('#am-email') as HTMLInputElement;
    input.value = 'friend@example.test';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(
      (fixture.nativeElement.querySelector('.dc-btn-accent') as HTMLButtonElement).disabled,
    ).toBe(true);
    http.expectOne('/api/groups/a').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain(
      'Could not load group members.',
    );
    (fixture.nativeElement.querySelector('.retry-members') as HTMLButtonElement).click();
    fixture.detectChanges();
    const add = fixture.nativeElement.querySelector('.dc-btn-accent') as HTMLButtonElement;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    add.click();
    http.expectNone((r) => r.method === 'POST');
    expect(add.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('[role=status]')?.textContent).toContain(
      'Loading group members',
    );
    expect(input.value).toBe('friend@example.test');
    http.expectOne('/api/groups/a').flush({ name: 'Home', members: [] });
    fixture.detectChanges();
    expect(input.value).toBe('friend@example.test');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
    expect(add.disabled).toBe(false);
    http.expectNone((r) => r.method === 'POST');
    add.click();
    const post = http.expectOne('/api/groups/a/members');
    expect(post.request.body).toEqual({ email: 'friend@example.test' });
    post.flush({
      name: 'Home',
      members: [{ id: 'friend', displayName: 'Friend', email: 'friend@example.test' }],
    });
    http.expectOne('/api/dashboard').flush({});
    fixture.detectChanges();
    expect(input.value).toBe('');
    expect(fixture.nativeElement.querySelector('.chips')?.textContent).toContain('Friend');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
    http.verify();
  });

  it('recovers from a failed roster retry with the email intact and another deliberate retry', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(ModalService).openAddMember('a');
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(AddMemberModalComponent);
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('#am-email') as HTMLInputElement;
    input.value = 'friend@example.test';
    input.dispatchEvent(new Event('input'));
    http.expectOne('/api/groups/a').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.retry-members') as HTMLButtonElement).click();
    fixture.detectChanges();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    http.expectNone((r) => r.method === 'POST');
    http.expectOne('/api/groups/a').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(input.value).toBe('friend@example.test');
    expect(fixture.nativeElement.querySelector('[role=status]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain(
      'Could not load group members.',
    );
    expect(
      (fixture.nativeElement.querySelector('.dc-btn-accent') as HTMLButtonElement).disabled,
    ).toBe(false);
    (fixture.nativeElement.querySelector('.retry-members') as HTMLButtonElement).click();
    http.expectOne('/api/groups/a').flush({ name: 'Home', members: [] });
    fixture.detectChanges();
    expect(input.value).toBe('friend@example.test');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.retry-members')).toBeNull();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    const post = http.expectOne('/api/groups/a/members');
    expect(post.request.body).toEqual({ email: 'friend@example.test' });
    post.flush({ name: 'Home', members: [] });
    http.expectOne('/api/dashboard').flush({});
    fixture.detectChanges();
    expect(input.value).toBe('');
    http.verify();
  });
});
