import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { ExpenseAttachmentsComponent } from './expense-attachments.component';

const base = '/api/groups/g1/expenses/e1/attachments';
const alice = { id: 'alice', email: 'a@example.com', displayName: 'Alice' };
const bob = { id: 'bob', email: 'b@example.com', displayName: 'Bob' };
const meta = (id: string, uploadedBy = alice, fileName = `receipt-${id}.pdf`) => ({
  id,
  expenseId: 'e1',
  uploadedBy,
  fileName,
  contentType: 'application/pdf',
  sizeBytes: 2048,
  sha256: 'x',
  createdAt: '2026-11-01T10:00:00Z',
});

@Component({
  imports: [ExpenseAttachmentsComponent],
  template: `<app-expense-attachments [base]="'/groups/g1/expenses/e1'" [readOnly]="readOnly" />`,
})
class Host {
  readOnly = false;
}

describe('ExpenseAttachmentsComponent', () => {
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
    http.expectOne(base).flush([meta('a1'), meta('a2', bob)]);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  async function pick(fixture: Awaited<ReturnType<typeof render>>, file: File) {
    const input = fixture.nativeElement.querySelector('input[type=file]') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('lists files, never renders them inline, and deletes only my own', async () => {
    const el: HTMLElement = (await render()).nativeElement;
    expect(el.textContent).toContain('receipt-a1.pdf');
    expect(el.textContent).toContain('2 KB');
    expect(el.querySelector('img, iframe, embed, object')).toBeNull();
    expect(el.querySelectorAll('[data-test=delete-attachment]').length).toBe(1);
  });

  it('rejects a 6 MB file and an .exe before any request', async () => {
    const fixture = await render();
    const big = new File([new Uint8Array(6 * 1024 * 1024)], 'big.pdf', { type: 'application/pdf' });
    await pick(fixture, big);
    expect(fixture.nativeElement.querySelector('[role=alert]')).not.toBeNull();
    await pick(fixture, new File(['x'], 'virus.exe', { type: 'application/x-msdownload' }));
    expect(fixture.nativeElement.querySelector('[role=alert]')).not.toBeNull();
    http.expectNone(base);
  });

  it('uploads an allowed file as multipart and lists it', async () => {
    const fixture = await render();
    await pick(fixture, new File(['%PDF'], 'new.pdf', { type: 'application/pdf' }));
    const req = http.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBe(true);
    expect((req.request.body as FormData).get('file')).toBeInstanceOf(File);
    req.flush(meta('a3', alice, 'new.pdf'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('new.pdf');
  });

  it('shows an actionable message for the server 413 and 400', async () => {
    const fixture = await render();
    await pick(fixture, new File(['%PDF'], 'a.pdf', { type: 'application/pdf' }));
    http.expectOne(base).flush({ message: 'big' }, { status: 413, statusText: 'x' });
    await fixture.whenStable();
    fixture.detectChanges();
    const first = fixture.nativeElement.querySelector('[role=alert]').textContent;
    await pick(fixture, new File(['%PDF'], 'b.pdf', { type: 'application/pdf' }));
    http.expectOne(base).flush({ message: 'type' }, { status: 400, statusText: 'x' });
    await fixture.whenStable();
    fixture.detectChanges();
    const second = fixture.nativeElement.querySelector('[role=alert]').textContent;
    expect(first).toContain('5 MB');
    expect(second).not.toBe(first);
  });

  it('downloads through a blob save and revokes the object URL', async () => {
    const create = vi.fn().mockReturnValue('blob:one');
    const revoke = vi.fn();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const fixture = await render();
    (
      fixture.nativeElement.querySelector('[data-test=download-attachment]') as HTMLButtonElement
    ).click();
    const req = http.expectOne(`${base}/a1`);
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x']));
    expect(create).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(revoke).toHaveBeenCalledWith('blob:one');
    click.mockRestore();
  });

  it('disables upload and delete when read-only', async () => {
    const el: HTMLElement = (await render(true)).nativeElement;
    expect((el.querySelector('input[type=file]') as HTMLInputElement).disabled).toBe(true);
    expect((el.querySelector('[data-test=delete-attachment]') as HTMLButtonElement).disabled).toBe(
      true,
    );
  });
});
