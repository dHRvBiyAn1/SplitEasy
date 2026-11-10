import { GroupSettingsComponent } from './group-settings.component';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { GroupDetailComponent } from './group-detail.component';
import { DashboardService } from '../dashboard/dashboard.service';
import { ModalService } from '../modals/modal.service';
import { SettleUpModalComponent } from '../modals/settle-up-modal.component';

const me = { id: 'currency-me', email: 'currency-me@example.test', displayName: 'Alex' };
const other = { id: 'currency-other', email: 'currency-other@example.test', displayName: 'Noor' };
const group = {
  id: 'currency-group',
  name: 'Trip',
  type: 'TRIP',
  createdBy: me,
  members: [me, other],
  simplifyDebts: true,
  createdAt: '2026-10-24T00:00:00Z',
};

describe('denominated group views', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: group.id })) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).login({ email: me.email, password: 'password123' }).subscribe();
    http.expectOne('/api/auth/login').flush({
      accessToken: 'currency-access-token',
      sessionId: 'currency-session',
      tokenType: 'Bearer',
      expiresIn: 3600,
      user: me,
    });
  });
  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });
  it('shows every member currency obligation and blocks leaving until all are settled', () => {
    const fixture = TestBed.createComponent(GroupSettingsComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush({ ...group, createdBy: other });
    http.expectOne('/api/groups/' + group.id + '/balances').flush({
      groupId: group.id,
      balances: [
        { user: me, netMinor: 1000, currency: 'USD' },
        { user: other, netMinor: -1000, currency: 'USD' },
        { user: me, netMinor: -1000, currency: 'JPY' },
        { user: other, netMinor: 1000, currency: 'JPY' },
      ],
    });
    http.expectOne('/api/groups/' + group.id + '/balances/current-obligations').flush([
      { from: other, to: me, amountMinor: 1000, currency: 'USD' },
      { from: me, to: other, amountMinor: 1000, currency: 'JPY' },
    ]);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('.mrow__bal')!.textContent).toContain('JPY');
    expect(root.querySelector('.mrow__bal')!.textContent).toContain('1,000');
    expect(root.querySelector('.mrow__bal')!.textContent).toContain('$10.00');
    const leave = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent?.trim() === 'Leave group',
    )!;
    expect(leave.disabled).toBe(true);
  });

  it('retains KWD in the group headline after recording its last settlement', () => {
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    http
      .expectOne('/api/groups/' + group.id + '/balances/mine')
      .flush([{ user: other, netMinor: 617, currency: 'KWD' }]);
    http.match('/api/groups/' + group.id + '/balances').forEach((request) =>
      request.flush({
        groupId: group.id,
        balances: [
          { user: me, netMinor: 617, currency: 'KWD' },
          { user: other, netMinor: -617, currency: 'KWD' },
        ],
      }),
    );
    http.expectOne('/api/groups/' + group.id + '/expenses').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.pill--overall').textContent).toContain('KWD');
    TestBed.inject(ModalService).openSettle({
      groupId: group.id,
      groupName: group.name,
      counterparty: other,
      netMinor: 617,
      currency: 'KWD',
    });
    const modal = TestBed.createComponent(SettleUpModalComponent);
    modal.detectChanges();
    modal.nativeElement.querySelector('.mform__actions .dc-btn-accent').click();
    const payment = http.expectOne('/api/groups/' + group.id + '/payments');
    expect(payment.request.body).toMatchObject({
      payerUserId: other.id,
      payeeUserId: me.id,
      amountMinor: 617,
      currency: 'KWD',
    });
    payment.flush({ id: 'kwd-settlement', ...payment.request.body });
    http.expectOne('/api/dashboard').flush({
      groups: [],
      people: [],
      settlements: [],
      activity: [],
      totals: [{ currency: 'KWD', totalNetMinor: 0, owedMinor: 0, oweMinor: 0 }],
    });
    http.expectOne('/api/groups/' + group.id).flush(group);
    http.expectOne('/api/groups/' + group.id + '/balances/mine').flush([]);
    http.match('/api/groups/' + group.id + '/balances').forEach((request) =>
      request.flush({
        groupId: group.id,
        balances: [
          { user: me, netMinor: 0, currency: 'KWD' },
          { user: other, netMinor: 0, currency: 'KWD' },
        ],
      }),
    );
    http.expectOne('/api/groups/' + group.id + '/expenses').flush([]);
    fixture.detectChanges();
    const headline = fixture.nativeElement.querySelector('.gd__pills').textContent;
    expect(headline).toMatch(/KWD\s+All square/);
    expect(headline).not.toContain('USD');
  });

  it('labels settled payment-only KWD separately from open JPY when the dashboard fails', () => {
    TestBed.inject(DashboardService).ensureLoaded();
    http.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    http
      .expectOne('/api/groups/' + group.id + '/balances/mine')
      .flush([{ user: other, netMinor: -500, currency: 'JPY' }]);
    http.expectOne('/api/groups/' + group.id + '/balances').flush({
      groupId: group.id,
      balances: [
        { user: me, netMinor: 0, currency: 'KWD' },
        { user: other, netMinor: 0, currency: 'KWD' },
        { user: me, netMinor: -500, currency: 'JPY' },
        { user: other, netMinor: 500, currency: 'JPY' },
      ],
    });
    http.expectOne('/api/groups/' + group.id + '/expenses').flush([]);
    fixture.detectChanges();
    const headlines = [...fixture.nativeElement.querySelectorAll('.pill--overall')].map(
      (pill: HTMLElement) => pill.textContent,
    );
    expect(headlines).toHaveLength(2);
    expect(headlines[0]).toMatch(/JPY\s+You owe\s+JPY\s*500/);
    expect(headlines[1]).toMatch(/KWD\s+All square/);
    expect(headlines.join(' ')).not.toContain('USD');
    expect(headlines.join(' ')).not.toContain('open balances');
  });

  it('keeps group net positions separate and renders KWD history and shares at three decimals', () => {
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    http.expectOne('/api/groups/' + group.id + '/balances/mine').flush([
      { user: other, netMinor: 1000, currency: 'USD' },
      { user: other, netMinor: -1000, currency: 'JPY' },
    ]);
    http.expectOne('/api/groups/' + group.id + '/balances').flush({
      groupId: group.id,
      balances: [{ user: me, netMinor: 0, currency: 'KWD' }],
    });
    http.expectOne('/api/groups/' + group.id + '/expenses').flush([
      {
        id: 'kwd-history',
        description: 'Kuwait lunch',
        amountMinor: 1234,
        currency: 'KWD',
        paidBy: me,
        participantCount: 2,
        category: 'FOOD_DRINK',
        spentOn: '2026-10-24',
        viewerDeltaMinor: 617,
        createdAt: '2026-10-24T00:00:00Z',
        version: 0,
      },
    ]);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('.gd__pills')!.textContent).toContain('JPY');
    expect(root.querySelector('.gd__pills')!.textContent).toContain('$10.00');
    expect(root.querySelector('.gd__pills')!.textContent).not.toContain('Even overall');
    expect(root.querySelector('.xrow')!.textContent).toMatch(/KWD\s*0\.617/);
    root.querySelector<HTMLButtonElement>('.xrow')!.click();
    http.expectOne('/api/groups/' + group.id + '/expenses/kwd-history').flush({
      id: 'kwd-history',
      groupId: group.id,
      description: 'Kuwait lunch',
      amountMinor: 1234,
      currency: 'KWD',
      paidBy: me,
      splitType: 'EQUAL',
      category: 'FOOD_DRINK',
      spentOn: '2026-10-24',
      participants: [
        { user: me, shareMinor: 617 },
        { user: other, shareMinor: 617 },
      ],
      createdAt: '2026-10-24T00:00:00Z',
      version: 0,
    });
    fixture.detectChanges();
    expect(root.querySelector('.xdetail')!.textContent).toMatch(/KWD\s*1\.234/);
    expect(root.querySelector('.xdetail')!.textContent).toMatch(/KWD\s*0\.617/);
  });
});
