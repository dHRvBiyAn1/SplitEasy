import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { GroupResponse } from '../groups/group.models';
import { SettleUpPanelComponent } from './settle-up-panel.component';

const GROUP: GroupResponse = {
  id: 'g1',
  name: 'Trip',
  createdBy: { id: 'u1', email: 'a@x.com', displayName: 'Alice' },
  members: [
    { id: 'u1', email: 'a@x.com', displayName: 'Alice' },
    { id: 'u2', email: 'b@x.com', displayName: 'Bob' },
    { id: 'u3', email: 'c@x.com', displayName: 'Carol' },
  ],
  createdAt: '2026-01-01T00:00:00Z',
};

describe('SettleUpPanelComponent', () => {
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpTesting = TestBed.inject(HttpTestingController);
  });

  it('seeds the form directly from a transaction prefill (Bob → Carol), no current-user inference', () => {
    const fixture = TestBed.createComponent(SettleUpPanelComponent);
    fixture.componentRef.setInput('group', GROUP);
    fixture.detectChanges(); // ngOnInit → loads history
    httpTesting.expectOne('/api/groups/g1/payments').flush([]);

    // A suggested transfer between two members who are NOT the current user.
    fixture.componentRef.setInput('prefill', {
      kind: 'transaction',
      payerUserId: 'u2',
      payeeUserId: 'u3',
      amountCents: 1234,
    });
    fixture.detectChanges(); // runs the effect

    const form = (fixture.componentInstance as unknown as { form: { getRawValue(): unknown } }).form;
    expect(form.getRawValue()).toEqual({ payerUserId: 'u2', payeeUserId: 'u3', amount: 12.34 });
  });
});
