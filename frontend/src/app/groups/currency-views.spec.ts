import { GroupSettingsComponent } from './group-settings.component';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { GroupDetailComponent } from './group-detail.component';

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

  it('keeps group net positions separate and renders KWD history and shares at three decimals', () => {
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    http.expectOne('/api/groups/' + group.id + '/balances/mine').flush([
      { user: other, netMinor: 1000, currency: 'USD' },
      { user: other, netMinor: -1000, currency: 'JPY' },
    ]);
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
