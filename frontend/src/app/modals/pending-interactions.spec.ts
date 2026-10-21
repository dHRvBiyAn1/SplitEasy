import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SessionService } from '../core/auth/session.service';
import { ModalService } from './modal.service';
import { ModalsComponent } from './modals.component';

const alice = { id: 'alice', displayName: 'Alice', email: 'alice@example.test' };
const bob = { id: 'bob', displayName: 'Bob', email: 'bob@example.test' };
const group = { id: 'a', name: 'Home', type: 'HOME', members: [alice, bob] };
const emptyDashboard = { groups: [], settlements: [], people: [], activity: [] };

describe('mutation close policy across the rendered global dialogs', () => {
  let http: HttpTestingController;
  let modal: ModalService;
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    localStorage.setItem('spliteasy.user', JSON.stringify(alice));
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    modal = TestBed.inject(ModalService);
  });
  afterEach(() => http.verify());
  function open(kind: string) {
    if (kind === 'expense') modal.openExpense('a');
    if (kind === 'payment')
      modal.openSettle({ groupId: 'a', groupName: 'Home', counterparty: bob, netCents: -1000 });
    if (kind === 'group') modal.openGroup();
    if (kind === 'member') modal.openAddMember('a');
    const fixture = TestBed.createComponent(ModalsComponent);
    fixture.detectChanges();
    if (kind === 'expense' || kind === 'member') http.expectOne('/api/groups/a').flush(group);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const fill = (selector: string, value: string) => {
      const input = root.querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    if (kind === 'expense') {
      fill('#ne-desc', 'Draft dinner');
      fill('#ne-amt', '10');
    }
    if (kind === 'group') fill('#ng-name', 'Draft home');
    if (kind === 'member') fill('#am-email', 'friend@example.test');
    fixture.detectChanges();
    if (kind === 'expense' || kind === 'group')
      root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    else root.querySelector<HTMLButtonElement>('.dc-btn-accent')!.click();
    fixture.detectChanges();
    const request = http.expectOne((r) => r.method === 'POST');
    return { fixture, root, request };
  }
  for (const kind of ['expense', 'payment', 'group', 'member']) {
    it(`${kind} keeps pending feedback through all close routes and releases closure after error`, () => {
      const { fixture, root, request } = open(kind);
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      root.querySelector<HTMLElement>('.mdl-backdrop')!.click();
      root.querySelector<HTMLButtonElement>('.mdl-close')!.click();
      root.querySelector<HTMLButtonElement>('.dc-btn-outline')?.click();
      fixture.detectChanges();
      expect(root.querySelector('[role=dialog]')).not.toBeNull();
      expect(root.textContent).toContain('Saving. Please wait.');
      request.flush({ message: 'Try again later.' }, { status: 503, statusText: 'Unavailable' });
      fixture.detectChanges();
      expect(root.textContent).toContain('Try again later.');
      expect(root.textContent).not.toContain('Saving. Please wait.');
      root.querySelector<HTMLButtonElement>('.mdl-close')!.click();
      fixture.detectChanges();
      expect(root.querySelector('[role=dialog]')).toBeNull();
      modal.openAppearance();
      fixture.detectChanges();
      expect(root.querySelector<HTMLButtonElement>('.mdl-close')!.disabled).toBe(false);
    });
    it(`${kind} force-closes on session reset and ignores the canceled result`, () => {
      const { fixture, root, request } = open(kind);
      TestBed.inject(SessionService).reset();
      fixture.detectChanges();
      expect(root.querySelector('[role=dialog]')).toBeNull();
      expect(request.cancelled).toBe(true);
      expect(modal.mutationPending()).toBe(false);
      http.expectNone('/api/dashboard');
    });
  }
  it('preserves payment operation identity across an uncertain error and dialog reopen', () => {
    const first = open('payment');
    const intent = first.request.request.body;
    first.request.error(new ProgressEvent('error'));
    first.fixture.detectChanges();
    first.root.querySelector<HTMLButtonElement>('.mdl-close')!.click();
    first.fixture.detectChanges();
    first.fixture.destroy();
    const retry = open('payment');
    expect(retry.request.request.body).toEqual(intent);
    retry.request.flush({});
    retry.fixture.detectChanges();
    http.expectOne('/api/dashboard').flush(emptyDashboard);
    expect(modal.active()).toBeNull();
    expect(modal.mutationPending()).toBe(false);
    expect(retry.root.querySelector('[role=dialog]')).toBeNull();
  });
});
