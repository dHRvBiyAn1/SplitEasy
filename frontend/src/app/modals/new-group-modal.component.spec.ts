import { storeTestSession } from '../core/auth/auth-test-fixture';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { ModalService } from './modal.service';
import { NewGroupModalComponent } from './new-group-modal.component';

const group = { id: 'created-group', name: 'Weekend', type: 'HOME', members: [] };

describe('new group member results', () => {
  let fixture: ComponentFixture<NewGroupModalComponent>;
  let requests: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;
  let refresh: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    storeTestSession({ id: 'owner', email: 'owner@example.test', displayName: 'Owner' });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    requests = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    refresh = vi.spyOn(TestBed.inject(DashboardService), 'refresh').mockImplementation(() => {});
    TestBed.inject(ModalService).openGroup();
    fixture = TestBed.createComponent(NewGroupModalComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    requests.verify();
    fixture.destroy();
  });

  function input(selector: string, value: string): void {
    const element = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    element.value = value;
    element.dispatchEvent(new Event('input'));
  }

  function create(emails: string[]): void {
    input('#ng-name', 'Weekend');
    for (const email of emails) {
      input('input[type=email]', email);
      fixture.componentInstance.addEmail();
    }
    fixture.componentInstance.save();
    requests.expectOne('/api/groups').flush(group);
  }

  function member(email: string, status = 200): void {
    const request = requests.expectOne('/api/groups/created-group/members');
    expect(request.request.body).toEqual({ email });
    request.flush(status === 200 ? group : {}, { status, statusText: 'Member result' });
  }

  function button(label: string): HTMLButtonElement {
    return [...fixture.nativeElement.querySelectorAll('button')].find(
      (element: any) => element.textContent.trim() === label,
    ) as HTMLButtonElement;
  }

  it('releases pending feedback if a dispatched group dialog is destroyed', () => {
    input('#ng-name', 'Weekend');
    fixture.componentInstance.save();
    const request = requests.expectOne('/api/groups');
    fixture.destroy();
    expect(request.cancelled).toBe(true);
    expect(TestBed.inject(ModalService).mutationPending()).toBe(false);
  });

  it('reports mixed results and retries failed emails in the existing group', () => {
    create([
      'added@example.com',
      'unknown@example.com',
      'existing@example.com',
      'busy@example.com',
    ]);
    member('added@example.com');
    member('unknown@example.com', 404);
    member('existing@example.com', 409);
    member('busy@example.com', 503);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Group created');
    expect(text).toContain('added@example.com — Added');
    expect(text).toContain('unknown@example.com — No account found with this email.');
    expect(text).toContain('existing@example.com — Already a member of this group.');
    expect(text).toContain('busy@example.com — Could not add this member. Please try again.');
    expect(navigate).not.toHaveBeenCalled();
    expect(TestBed.inject(ModalService).active()).toBe('group');
    expect(refresh).toHaveBeenCalledOnce();
    button('Retry failed additions').click();
    requests.expectNone('/api/groups');
    member('unknown@example.com');
    member('existing@example.com', 409);
    member('busy@example.com');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('busy@example.com — Added');
    expect(fixture.nativeElement.textContent).toContain('added@example.com — Added');
    expect(fixture.nativeElement.textContent).toContain('existing@example.com — Already a member');
    button('View group').click();
    expect(navigate).toHaveBeenCalledExactlyOnceWith(['/groups', 'created-group']);
  });

  it('cancels an in-flight retry and queued mutations on session change', () => {
    create(['first@example.com', 'second@example.com']);
    member('first@example.com', 503);
    member('second@example.com', 503);
    fixture.detectChanges();
    button('Retry failed additions').click();
    const pending = requests.expectOne('/api/groups/created-group/members');
    TestBed.inject(SessionService).reset();
    expect(pending.cancelled).toBe(true);
    requests.expectNone('/api/groups/created-group/members');
    expect(refresh).toHaveBeenCalledOnce();
    expect(navigate).not.toHaveBeenCalled();
    expect(TestBed.inject(ModalService).active()).toBeNull();
  });

  it('keeps successful member results visible without offering retry or creating another group', () => {
    create(['added@example.com']);
    fixture.componentInstance.save();
    requests.expectNone('/api/groups');
    member('added@example.com');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('All requested members were added.');
    expect(fixture.nativeElement.textContent).toContain('added@example.com — Added');
    expect(button('Retry failed additions')).toBeUndefined();
    expect(navigate).not.toHaveBeenCalled();
    button('View group').click();
    expect(navigate).toHaveBeenCalledExactlyOnceWith(['/groups', 'created-group']);
  });

  it('opens a group created without requested members', () => {
    create([]);
    expect(navigate).toHaveBeenCalledExactlyOnceWith(['/groups', 'created-group']);
    expect(TestBed.inject(ModalService).active()).toBeNull();
  });
});
