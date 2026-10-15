import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

/** Synchronous session boundaries shared by auth and account-owned state, without HTTP dependencies. */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private revision = 0;
  private readonly resets = new Subject<void>();
  readonly changes = this.resets.asObservable();

  get version(): number {
    return this.revision;
  }

  reset(): void {
    this.revision++;
    this.resets.next();
  }
}
