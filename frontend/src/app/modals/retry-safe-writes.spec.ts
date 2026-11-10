import { storeTestSession, testAuthResponse } from '../core/auth/auth-test-fixture';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { appConfig } from '../app.config';
import { ProfileComponent } from '../profile/profile.component';
import { ApplicationInitStatus, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ModalService } from './modal.service';
import { SettleUpModalComponent } from './settle-up-modal.component';
import { NewExpenseModalComponent } from './new-expense-modal.component';
import { NewGroupModalComponent } from './new-group-modal.component';

@Component({ template: '' })
class Destination {}
// Expense-only fixtures keep the default USD draft while profile loading is unavailable.
function settleProfileRead(http: HttpTestingController): void {
  for (const request of http.match('/api/me'))
    if (!request.cancelled) request.flush({}, { status: 503, statusText: 'Unavailable' });
}

const actor = { id: 'owner', email: 'owner@example.test', displayName: 'Owner' };

describe('retry-safe rendered writes', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<NewGroupModalComponent>;
  function open(restore = false) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'groups/:id', component: Destination }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(actor, restore);
    TestBed.inject(ModalService).openGroup();
    fixture = TestBed.createComponent(NewGroupModalComponent);
    fixture.detectChanges();
  }
  function fill(selector: string, value: string) {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }
  function submit() {
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
  }
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    open();
  });
  afterEach(() => {
    try {
      settleProfileRead(http);
      fixture.destroy();
      http.verify();
    } finally {
      TestBed.resetTestingModule();
      sessionStorage.clear();
    }
  });

  it('restores an uncertain group draft and reuses its identity after a full application reload', () => {
    fill('#ng-name', 'Weekend');
    fill('input[type=email]', 'friend@example.test');
    fixture.componentInstance.addEmail();
    submit();
    const first = http.expectOne('/api/groups');
    const original = first.request.body;
    expect(original.operationId).toEqual(expect.any(String));
    first.error(new ProgressEvent('error'));
    fixture.destroy();
    TestBed.resetTestingModule();
    open(true);
    expect((fixture.nativeElement.querySelector('#ng-name') as HTMLInputElement).value).toBe(
      'Weekend',
    );
    expect(fixture.nativeElement.textContent).toContain('friend@example.test');
    expect(fixture.nativeElement.textContent).toContain('may already have been saved');
    http.expectNone((request) => request.method === 'POST');
    submit();
    const retry = http.expectOne('/api/groups');
    expect(retry.request.body).toEqual(original);
    retry.flush({ message: 'Still unavailable.' }, { status: 503, statusText: 'Unavailable' });
  });
  it('blocks dispatch with actionable feedback if the retry identity cannot be persisted', () => {
    fill('#ng-name', 'Storage blocked');
    const persist = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage unavailable', 'QuotaExceededError');
    });
    try {
      submit();
      http.expectNone((request) => request.method === 'POST');
      expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain(
        'Enable browser storage',
      );
      expect(fixture.nativeElement.querySelector('button[type=submit]').disabled).toBe(false);
    } finally {
      persist.mockRestore();
    }
    submit();
    const request = http.expectOne('/api/groups');
    expect(request.request.body).toEqual({
      name: 'Storage blocked',
      type: 'HOME',
      operationId: expect.any(String),
    });
    request.error(new ProgressEvent('error'));
  });

  it('blocks changed uncertain intent and lets the user retry the original group explicitly', () => {
    fill('#ng-name', 'Original trip');
    submit();
    const first = http.expectOne('/api/groups');
    const original = first.request.body;
    first.error(new ProgressEvent('error'));
    fill('#ng-name', 'Changed trip');
    submit();
    http.expectNone((request) => request.method === 'POST');
    expect(fixture.nativeElement.textContent).toContain('may already have been saved');
    const retry = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: any) => button.textContent.trim() === 'Retry original group',
    ) as HTMLButtonElement | undefined;
    expect(retry).toBeDefined();
    retry!.click();
    const request = http.expectOne('/api/groups');
    expect(request.request.body).toEqual(original);
    request.flush({ message: 'Operation conflict.' }, { status: 409, statusText: 'Conflict' });
    fixture.destroy();
    TestBed.resetTestingModule();
    open(true);
    expect((fixture.nativeElement.querySelector('#ng-name') as HTMLInputElement).value).toBe(
      'Original trip',
    );
  });

  it('clears reload-persisted retries when signing out before opening any write dialog', async () => {
    fill('#ng-name', 'Original trip');
    submit();
    const first = http.expectOne('/api/groups');
    const originalId = first.request.body.operationId;
    first.error(new ProgressEvent('error'));
    fixture.destroy();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/auth/refresh').flush(testAuthResponse(actor));
    await TestBed.inject(ApplicationInitStatus).donePromise;
    const profile = TestBed.createComponent(ProfileComponent);
    profile.detectChanges();
    http.expectOne('/api/me').flush({
      ...actor,
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2026-01-01T00:00:00Z',
    });
    (profile.nativeElement.querySelector('.profile__signout') as HTMLButtonElement).click();
    http.expectOne('/api/auth/logout').flush(null);
    await profile.whenStable();
    profile.destroy();
    settleProfileRead(http);
    http.verify();
    TestBed.resetTestingModule();
    open(false);
    expect((fixture.nativeElement.querySelector('#ng-name') as HTMLInputElement).value).toBe('');
    fill('#ng-name', 'Original trip');
    submit();
    const separate = http.expectOne('/api/groups');
    expect(separate.request.body.operationId).not.toBe(originalId);
    separate.error(new ProgressEvent('error'));
  });

  it('starts a distinct intent only after explicit acknowledgement of the uncertain original', () => {
    fill('#ng-name', 'Original trip');
    submit();
    const first = http.expectOne('/api/groups');
    const originalId = first.request.body.operationId;
    first.error(new ProgressEvent('error'));
    fill('#ng-name', 'Separate trip');
    submit();
    http.expectNone((request) => request.method === 'POST');
    const button = [...fixture.nativeElement.querySelectorAll('button')].find(
      (element: any) => element.textContent.trim() === 'Start a separate group',
    ) as HTMLButtonElement;
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(false);
    button.click();
    submit();
    http.expectNone((request) => request.method === 'POST');
    confirmation.mockReturnValue(true);
    button.click();
    submit();
    expect(confirmation).toHaveBeenCalledWith(
      expect.stringContaining('may already have been saved'),
    );
    const separate = http.expectOne('/api/groups');
    expect(separate.request.body.name).toBe('Separate trip');
    expect(separate.request.body.operationId).not.toBe(originalId);
    separate.error(new ProgressEvent('error'));
    confirmation.mockRestore();
  });
});

describe('retry-safe rendered expense drafts', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<NewExpenseModalComponent>;
  const group = { id: 'g', name: 'Trip', members: [actor] };
  function open(restore = false) {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(actor, restore);
    TestBed.inject(ModalService).openExpense('g');
    fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    fixture.detectChanges();
  }
  function fill(selector: string, value: string) {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }
  function submit() {
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
  }
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    open();
  });
  afterEach(() => {
    try {
      settleProfileRead(http);
      fixture.destroy();
      http.verify();
    } finally {
      TestBed.resetTestingModule();
      sessionStorage.clear();
    }
  });

  it('restores a lost-response expense draft and requires deliberate identical retry after reload', () => {
    fill('#ne-desc', 'Dinner');
    fill('#ne-amt', '12.34');
    fill('#ne-date', '2026-10-18');
    submit();
    const first = http.expectOne('/api/groups/g/expenses');
    const original = first.request.body;
    expect(original.operationId).toEqual(expect.any(String));
    first.error(new ProgressEvent('error'));
    fixture.destroy();
    TestBed.resetTestingModule();
    open(true);
    expect((fixture.nativeElement.querySelector('#ne-desc') as HTMLInputElement).value).toBe(
      'Dinner',
    );
    expect((fixture.nativeElement.querySelector('#ne-amt') as HTMLInputElement).value).toBe(
      '12.34',
    );
    expect((fixture.nativeElement.querySelector('#ne-date') as HTMLInputElement).value).toBe(
      '2026-10-18',
    );
    http.expectNone((request) => request.method === 'POST');
    submit();
    const retry = http.expectOne('/api/groups/g/expenses');
    expect(retry.request.body).toEqual(original);
    retry.error(new ProgressEvent('error'));
  });
  it('submits the loaded financial version and retains the draft after stale-edit conflict', () => {
    fixture.destroy();
    TestBed.inject(ModalService).openExpense('g', 'e');
    fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    http.expectOne('/api/groups/g/expenses/e').flush({
      id: 'e',
      groupId: 'g',
      version: 4,
      description: 'Original',
      amountMinor: 1200,
      currency: 'USD' as const,
      paidBy: actor,
      splitType: 'EQUAL',
      category: 'OTHER',
      spentOn: '2026-10-18',
      participantUserIds: [actor.id],
      participants: [{ user: actor, shareMinor: 1200 }],
    });
    fixture.detectChanges();
    fill('#ne-desc', 'My typed correction');
    submit();
    const edit = http.expectOne('/api/groups/g/expenses/e');
    expect(edit.request.method).toBe('PUT');
    expect(edit.request.body.expectedVersion).toBe(4);
    edit.flush(
      { message: 'This expense changed. Reload and review the latest changes.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('#ne-desc') as HTMLInputElement).value).toBe(
      'My typed correction',
    );
    expect(fixture.nativeElement.textContent).toContain('Reload and review');
    http.expectNone((request) => request.method === 'PUT');
  });
});

describe('retry-safe rendered payment drafts', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<SettleUpModalComponent>;
  const settlement = {
    groupId: 'g',
    groupName: 'Trip',
    counterparty: { id: 'friend', email: 'friend@example.test', displayName: 'Friend' },
    netMinor: -1000,
  };
  function open(prefill = true, restore = false) {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(actor, restore);
    TestBed.inject(ModalService).openSettle(prefill ? settlement : undefined);
    fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
  }
  function submit() {
    fixture.nativeElement.querySelector('.dc-btn-accent').click();
    fixture.detectChanges();
  }
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    open();
  });
  afterEach(() => {
    try {
      settleProfileRead(http);
      fixture.destroy();
      http.verify();
    } finally {
      TestBed.resetTestingModule();
      sessionStorage.clear();
    }
  });

  it('restores a partial payment and its original identity across a complete reload without sending automatically', () => {
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = '4.56';
    input.dispatchEvent(new Event('input'));
    submit();
    const first = http.expectOne('/api/groups/g/payments');
    const original = first.request.body;
    first.error(new ProgressEvent('error'));
    fixture.destroy();
    TestBed.resetTestingModule();
    open(false, true);
    expect((fixture.nativeElement.querySelector('input') as HTMLInputElement | null)?.value).toBe(
      '4.56',
    );
    expect(fixture.nativeElement.textContent).toContain('Friend');
    http.expectNone((request) => request.method === 'POST');
    submit();
    const retry = http.expectOne('/api/groups/g/payments');
    expect(retry.request.body).toEqual(original);
    retry.error(new ProgressEvent('error'));
  });
  it('blocks a changed uncertain payment until the user explicitly acknowledges a separate payment', () => {
    submit();
    const first = http.expectOne('/api/groups/g/payments');
    const original = first.request.body;
    first.error(new ProgressEvent('error'));
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = '4.56';
    input.dispatchEvent(new Event('input'));
    submit();
    http.expectNone((request) => request.method === 'POST');
    expect(fixture.nativeElement.textContent).toContain('may already have been saved');
    const button = [...fixture.nativeElement.querySelectorAll('button')].find(
      (element: any) => element.textContent.trim() === 'Start a separate payment',
    ) as HTMLButtonElement;
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(false);
    try {
      button.click();
      submit();
      http.expectNone((request) => request.method === 'POST');
      confirmation.mockReturnValue(true);
      button.click();
      submit();
      expect(confirmation).toHaveBeenCalledWith(
        expect.stringContaining('may already have been recorded'),
      );
      const separate = http.expectOne('/api/groups/g/payments');
      expect(separate.request.body).toEqual({
        ...original,
        amountMinor: 456,
        currency: 'USD' as const,
        operationId: expect.any(String),
      });
      expect(separate.request.body.operationId).not.toBe(original.operationId);
      separate.error(new ProgressEvent('error'));
    } finally {
      confirmation.mockRestore();
    }
  });
});
