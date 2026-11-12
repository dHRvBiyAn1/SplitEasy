import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { GroupDetailComponent } from './group-detail.component';

const me = { id: 'me', displayName: 'Alex Rivera', email: 'alex@test.com' };
const bob = { id: 'bob', displayName: 'Bob Chen', email: 'bob@test.com', accountId: 'bob-acct' };
const ann = { id: 'ann', displayName: 'Ann Lee', email: 'ann@test.com' };
const guest = {
  id: 'guest',
  displayName: 'Gus Guest',
  email: null,
  accountId: null as string | null,
};

describe('Remind action on an owed-to-me balance', () => {
  let http: HttpTestingController;

  function render() {
    TestBed.configureTestingModule({
      imports: [GroupDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'g' })) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush({
      id: 'g',
      name: 'Trip',
      type: 'TRIP',
      createdBy: me,
      members: [me, bob, ann, guest],
      simplifyDebts: true,
    });
    http.expectOne('/api/groups/g/balances/mine').flush([
      { user: bob, netMinor: 1500, currency: 'USD' },
      { user: ann, netMinor: -500, currency: 'USD' },
      { user: guest, netMinor: 700, currency: 'USD' },
    ]);
    http.expectOne('/api/groups/g/balances').flush({ balances: [] });
    http.expectOne('/api/groups/g/expenses').flush([]);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  const reminders = (root: HTMLElement) =>
    [...root.querySelectorAll<HTMLButtonElement>('button')].filter((b) =>
      b.textContent?.includes('Remind'),
    );

  it('is offered only for people with an account who owe me, and posts the debtor account and currency', () => {
    const fixture = render();
    const root: HTMLElement = fixture.nativeElement;
    expect(reminders(root)).toHaveLength(1);
    reminders(root)[0].click();
    const req = http.expectOne('/api/groups/g/reminders');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ debtorId: 'bob-acct', currency: 'USD' });
    req.flush({ debtorId: 'bob-acct', currency: 'USD', amountMinor: 1500 });
    fixture.detectChanges();
    expect(root.textContent).toContain('Reminder sent to Bob');
  });

  it('shows the server reason when a reminder was already sent today', () => {
    const fixture = render();
    const root: HTMLElement = fixture.nativeElement;
    reminders(root)[0].click();
    http
      .expectOne('/api/groups/g/reminders')
      .flush({ message: 'You already sent this reminder today' }, { status: 409, statusText: 'x' });
    fixture.detectChanges();
    expect(root.querySelector('[role=alert]')?.textContent).toContain('already sent');
  });
});
