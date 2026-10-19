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
    http.expectOne('/api/groups/a').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain(
      'Could not load group members.',
    );
    (fixture.nativeElement.querySelector('.retry-members') as HTMLButtonElement).click();
    http.expectOne('/api/groups/a').flush({ name: 'Home', members: [] });
    fixture.detectChanges();
    expect(input.value).toBe('friend@example.test');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
    http.expectNone((r) => r.method === 'POST');
    http.verify();
  });
});
