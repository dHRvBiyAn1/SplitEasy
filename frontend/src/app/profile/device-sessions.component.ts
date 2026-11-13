import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { takeUntil } from 'rxjs';
import { DeviceSession } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { LocaleDatePipe } from '../core/i18n/locale-date.pipe';
import { TranslatePipe } from '../core/i18n/translate.pipe';

@Component({
  selector: 'app-device-sessions',
  imports: [TranslatePipe, LocaleDatePipe],
  template: `
    <section class="dc-card" aria-labelledby="device-sessions-title">
      <h2 id="device-sessions-title">{{ 'deviceSessions.devicesAndSessions' | t }}</h2>
      <p>{{ 'deviceSessions.tabsInTheSame' | t }}</p>
      <button type="button" class="dc-btn-outline" [disabled]="busy()" (click)="load()">
        {{ opened() ? 'Refresh sessions' : 'Manage sessions' }}
      </button>
      @if (busy()) {
        <p role="status">{{ 'deviceSessions.updatingSessions' | t }}</p>
      }
      @if (error()) {
        <p role="alert">{{ error() }}</p>
      }
      @if (message()) {
        <p role="status">{{ message() }}</p>
      }
      @if (opened()) {
        <ul>
          @for (device of devices(); track device.id) {
            <li>
              <div>
                <strong>{{ device.deviceLabel }}</strong>
                @if (device.current) {
                  <span> {{ 'deviceSessions.thisDevice' | t }}</span>
                }
                <p [id]="'session-details-' + device.id">
                  {{
                    'deviceSessions.createdV1LastRenewed'
                      | t
                        : {
                            v1: (device.createdAt | ldate),
                            v2: (device.refreshedAt | ldate),
                            v3: (device.expiresAt | ldate),
                          }
                  }}
                </p>
              </div>
              <button
                type="button"
                class="dc-btn-outline"
                [disabled]="busy()"
                [attr.aria-label]="
                  device.current ? 'Sign out this device' : 'Sign out ' + device.deviceLabel
                "
                [attr.aria-describedby]="'session-details-' + device.id"
                (click)="revoke(device)"
              >
                {{ device.current ? 'Sign out this device' : 'Sign out device' }}
              </button>
            </li>
          }
        </ul>
        <button type="button" class="dc-btn-outline" [disabled]="busy()" (click)="logoutAll()">
          {{ 'deviceSessions.signOutAllDevices' | t }}
        </button>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    section {
      padding: var(--sp-6);
    }
    h2 {
      margin-top: 0;
    }
    ul {
      list-style: none;
      padding: 0;
      margin-bottom: 0;
    }
    li {
      border-top: 1px solid var(--line);
      margin-top: var(--sp-4);
      padding-top: var(--sp-4);
      display: flex;
      flex-wrap: wrap;
      gap: var(--sp-3);
      align-items: start;
      justify-content: space-between;
    }
    li div {
      flex: 1 1 16rem;
      overflow-wrap: anywhere;
    }
    li p {
      color: var(--ink2);
    }
  `,
})
export class DeviceSessionsComponent {
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  protected readonly devices = signal<DeviceSession[]>([]);
  protected readonly opened = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly message = signal('');

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.devices.set([]);
      this.opened.set(false);
      this.busy.set(false);
      this.error.set('');
      this.message.set('');
    });
  }

  load(): void {
    if (this.busy() || !this.auth.user()) return;
    this.opened.set(true);
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.devices.set([]);
    this.auth
      .listSessions()
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (devices) => {
          this.devices.set(devices);
          this.busy.set(false);
        },
        error: () => {
          this.busy.set(false);
          this.error.set('Unable to load sessions. Refresh sessions to try again.');
        },
      });
  }

  logoutAll(): void {
    if (this.busy() || !this.auth.user()) return;
    this.auth.logoutAll();
    void this.router.navigate(['/login']);
  }

  revoke(device: DeviceSession): void {
    if (this.busy() || !this.auth.user()) return;
    if (device.current) {
      this.auth.logout();
      void this.router.navigate(['/login']);
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    this.auth
      .revokeSession(device.id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.devices.update((devices) =>
            devices.filter((candidate) => candidate.id !== device.id),
          );
          this.busy.set(false);
          this.message.set('Device signed out.');
        },
        error: () => {
          this.busy.set(false);
          this.error.set('Device sign-out could not be confirmed. Try again or refresh sessions.');
        },
      });
  }
}
