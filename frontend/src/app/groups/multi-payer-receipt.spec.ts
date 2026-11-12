import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { GroupDetailComponent } from './group-detail.component';

const me = { id: 'me', displayName: 'Alex Rivera', email: 'alex@test.com' };
const ann = { id: 'ann', displayName: 'Ann Lee', email: 'ann@test.com' };
const bob = { id: 'bob', displayName: 'Bob Chen', email: 'bob@test.com' };

describe('multi-payer receipt rendering', () => {
  it('summarises several payers and lists each contributor in the receipt', () => {
    TestBed.configureTestingModule({
      imports: [GroupDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'g' })) } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush({
      id: 'g',
      name: 'Trip',
      type: 'TRIP',
      createdBy: me,
      members: [me, ann, bob],
      simplifyDebts: true,
    });
    http.expectOne('/api/groups/g/balances/mine').flush([]);
    http.expectOne('/api/groups/g/balances').flush({ balances: [] });
    const summary = {
      id: 'e',
      description: 'Dinner',
      paidBy: ann,
      payerCount: 3,
      amountMinor: 1000,
      currency: 'USD',
      category: 'OTHER',
      spentOn: '2026-11-12',
      viewerDeltaMinor: 0,
      participantCount: 3,
    };
    http.expectOne('/api/groups/g/expenses').flush([summary]);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('Ann and 2 others paid $10.00');
    root.querySelector<HTMLButtonElement>('.xrow')!.click();
    http.expectOne('/api/groups/g/expenses/e').flush({
      ...summary,
      version: 0,
      groupId: 'g',
      splitType: 'EQUAL',
      participants: [
        { user: me, shareMinor: 334 },
        { user: ann, shareMinor: 333 },
        { user: bob, shareMinor: 333 },
      ],
      contributions: [
        { user: ann, amountMinor: 600, currency: 'USD' },
        { user: bob, amountMinor: 300, currency: 'USD' },
        { user: me, amountMinor: 100, currency: 'USD' },
      ],
    });
    fixture.detectChanges();
    const paid = [...root.querySelectorAll('.xdetail__line--paid')].map((l) => l.textContent);
    expect(paid).toHaveLength(3);
    expect(paid[0]).toContain('Ann paid');
    expect(paid[0]).toContain('$6.00');
    expect(paid[2]).toContain('You paid');
    http.verify();
    TestBed.resetTestingModule();
  });
});
