import { Injectable, InjectionToken, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';

import { ModalService } from '../../modals/modal.service';

export const PWA_RELOAD = new InjectionToken<() => void>('PWA_RELOAD', {
  providedIn: 'root',
  factory: () => () => location.reload(),
});

const RELOADED_KEY = 'spliteasy.pwa.reloaded';

/** Applies a downloaded app version after the next completed navigation, never while a modal draft is open. App shell only. */
@Injectable({ providedIn: 'root' })
export class PwaUpdateService {
  private readonly updates = inject(SwUpdate);
  private readonly reload = inject(PWA_RELOAD);
  private readonly modal = inject(ModalService);
  private ready = false;

  constructor() {
    if (!this.updates.isEnabled) return;
    this.updates.versionUpdates.subscribe((e) => {
      if (e.type === 'VERSION_READY') this.ready = true;
    });
    inject(Router).events.subscribe((e) => {
      if (this.ready && e instanceof NavigationEnd && !this.modal.active()) {
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
