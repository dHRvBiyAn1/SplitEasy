import { Injectable, signal, untracked } from '@angular/core';
import { Subject } from 'rxjs';

/** Synchronous session boundaries shared by auth and account-owned state, without HTTP dependencies. */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly revision = signal(0);
  readonly currentVersion = this.revision.asReadonly();
  private readonly resets = new Subject<void>();
  readonly changes = this.resets.asObservable();

  get version(): number {
    return untracked(this.revision);
  }

  reset(): void {
    this.revision.update((value) => value + 1);
    this.resets.next();
  }
}
