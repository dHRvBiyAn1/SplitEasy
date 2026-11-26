import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { ExpenseCommentsComponent } from './expense-comments.component';

const base = '/api/groups/g1/expenses/e1/comments';
const alice = { id: 'alice', email: 'a@example.com', displayName: 'Alice' };
const bob = { id: 'bob', email: 'b@example.com', displayName: 'Bob' };
const comment = (id: string, author = alice, body = `Body ${id}`) => ({
  id,
  expenseId: 'e1',
  author,
  body,
  createdAt: '2026-11-01T10:00:00Z',
});

@Component({
  imports: [ExpenseCommentsComponent],
  template: `<app-expense-comments [base]="'/groups/g1/expenses/e1'" [readOnly]="readOnly" />`,
})
class Host {
  readOnly = false;
}

describe('ExpenseCommentsComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(alice);
    vi.spyOn(TestBed.inject(AuthService), 'isAuthenticated').mockReturnValue(true);
  });

  async function render(readOnly = false) {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.readOnly = readOnly;
    fixture.detectChanges();
    http.expectOne(base).flush([comment('c1'), comment('c2', bob)]);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('lists comments and offers delete only on my own', async () => {
    const el: HTMLElement = (await render()).nativeElement;
    expect(el.textContent).toContain('Body c1');
    expect(el.textContent).toContain('Bob');
    expect(el.querySelectorAll('[data-test=delete-comment]').length).toBe(1);
  });

  it('adds a comment with the typed body and appends it', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    const box = el.querySelector('textarea') as HTMLTextAreaElement;
    expect(box.maxLength).toBe(2000);
    box.value = '  Thanks!  ';
    box.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ body: 'Thanks!' });
    req.flush(comment('c3', alice, 'Thanks!'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('Thanks!');
    expect((el.querySelector('textarea') as HTMLTextAreaElement).value).toBe('');
  });

  it('deletes my own comment', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('[data-test=delete-comment]') as HTMLButtonElement).click();
    const req = http.expectOne(`${base}/c1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).not.toContain('Body c1');
    expect(el.textContent).toContain('Body c2');
  });

  it('keeps the draft and says so when adding fails', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    const box = el.querySelector('textarea') as HTMLTextAreaElement;
    box.value = 'Hello';
    box.dispatchEvent(new Event('input'));
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http.expectOne(base).flush({ message: 'no' }, { status: 500, statusText: 'x' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')).not.toBeNull();
    expect((el.querySelector('textarea') as HTMLTextAreaElement).value).toBe('Hello');
  });

  it('disables writing and deleting when read-only', async () => {
    const el: HTMLElement = (await render(true)).nativeElement;
    expect((el.querySelector('textarea') as HTMLTextAreaElement).disabled).toBe(true);
    expect((el.querySelector('[data-test=delete-comment]') as HTMLButtonElement).disabled).toBe(
      true,
    );
  });
});
