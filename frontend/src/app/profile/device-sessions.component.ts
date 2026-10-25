import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { takeUntil } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { DeviceSession } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';

@Component({
  selector: 'app-device-sessions',
  imports: [DatePipe],
  template: `
    <section class="dc-card" aria-labelledby="device-sessions-title">
      <h2 id="device-sessions-title">Devices and sessions</h2>
      <p>Tabs in the same browser share a sign-in. Sign out any device you no longer use.</p>
      <button type="button" class="dc-btn-outline" [disabled]="busy()" (click)="load()">
        {{ opened() ? 'Refresh sessions' : 'Manage sessions' }}
      </button>
      @if (busy()) {
        <p role="status">Updating sessions…</p>
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
                  <span> — This device</span>
                }
                <p [id]="'session-details-' + device.id">
                  Created {{ device.createdAt | date: 'medium' }}. Last renewed
                  {{ device.refreshedAt | date: 'medium' }}. Expires
                  {{ device.expiresAt | date: 'medium' }}.
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
          Sign out all devices
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
  private readonly api = inject(ApiService);
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
    this.api
      .get<DeviceSession[]>('/me/sessions')
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
    this.api
      .delete<void>('/me/sessions/' + device.id)
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
