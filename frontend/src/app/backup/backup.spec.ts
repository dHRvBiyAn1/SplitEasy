import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DownloadMyDataComponent } from './download-my-data.component';

describe('DownloadMyDataComponent', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('saves the ZIP under a dated name, shows pending, and revokes the URL', async () => {
    const create = vi.fn().mockReturnValue('blob:zip');
    const revoke = vi.fn();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }));
    const names: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(this.download);
    });
    const fixture = TestBed.createComponent(DownloadMyDataComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect((el.querySelector('button') as HTMLButtonElement).disabled).toBe(true);
    const req = http.expectOne('/api/me/export');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['zip']));
    expect(names[0]).toMatch(/^spliteasy-export-\d{4}-\d{2}-\d{2}\.zip$/);
    expect(revoke).toHaveBeenCalledWith('blob:zip');
    click.mockRestore();
  });

  it('reports a failure and allows another try', async () => {
    const fixture = TestBed.createComponent(DownloadMyDataComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('button') as HTMLButtonElement).click();
    http.expectOne('/api/me/export').error(new ProgressEvent('error'), { status: 500 });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')).not.toBeNull();
    expect((el.querySelector('button') as HTMLButtonElement).disabled).toBe(false);
  });
});
