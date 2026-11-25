import { Injectable, InjectionToken, inject } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';

export const PWA_RELOAD = new InjectionToken<() => void>('PWA_RELOAD', {
  providedIn: 'root',
  factory: () => () => location.reload(),
});

const RELOADED_KEY = 'spliteasy.pwa.reloaded';

/** Applies a downloaded app version at the next navigation, never mid-form. App shell only. */
@Injectable({ providedIn: 'root' })
export class PwaUpdateService {
  private readonly updates = inject(SwUpdate);
  private readonly reload = inject(PWA_RELOAD);
  private ready = false;

  constructor() {
    if (!this.updates.isEnabled) return;
    this.updates.versionUpdates.subscribe((e) => {
      if (e.type === 'VERSION_READY') this.ready = true;
    });
    inject(Router).events.subscribe((e) => {
      if (this.ready && e instanceof NavigationStart) {
        this.ready = false;
        void this.updates.activateUpdate().then(() => this.reload());
      }
    });
    this.updates.unrecoverable.subscribe(() => {
      // shortcut: one reload per tab session guards against a reload loop; a persistent fault needs a redeploy.
      if (sessionStorage.getItem(RELOADED_KEY)) return;
      sessionStorage.setItem(RELOADED_KEY, '1');
      this.reload();
    });
  }
}
