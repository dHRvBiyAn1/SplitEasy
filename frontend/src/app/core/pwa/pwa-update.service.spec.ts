import { TestBed } from '@angular/core/testing';
import { Router, NavigationStart } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { Subject } from 'rxjs';
import { PwaUpdateService, PWA_RELOAD } from './pwa-update.service';

describe('PwaUpdateService', () => {
  let versions: Subject<unknown>;
  let unrecoverable: Subject<unknown>;
  let routerEvents: Subject<unknown>;
  let reload: ReturnType<typeof vi.fn>;
  let activate: ReturnType<typeof vi.fn>;

  function setup(enabled = true) {
    versions = new Subject();
    unrecoverable = new Subject();
    routerEvents = new Subject();
    reload = vi.fn();
    activate = vi.fn().mockResolvedValue(true);
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: SwUpdate,
          useValue: {
            isEnabled: enabled,
            versionUpdates: versions,
            unrecoverable,
            activateUpdate: activate,
          },
        },
        { provide: Router, useValue: { events: routerEvents } },
        { provide: PWA_RELOAD, useValue: reload },
      ],
    });
    TestBed.inject(PwaUpdateService);
  }
  const flush = () => new Promise((r) => setTimeout(r));

  it('does not reload when a new version becomes ready, only at the next navigation', async () => {
    setup();
    versions.next({ type: 'VERSION_READY' });
    await flush();
    expect(activate).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
    routerEvents.next(new NavigationStart(1, '/groups'));
    await flush();
    expect(activate).toHaveBeenCalledTimes(1);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('ignores navigation and other version events while no update is ready', async () => {
    setup();
    versions.next({ type: 'VERSION_DETECTED' });
    routerEvents.next(new NavigationStart(1, '/groups'));
    await flush();
    expect(reload).not.toHaveBeenCalled();
  });

  it('reloads once on an unrecoverable worker state, never in a loop', () => {
    setup();
    unrecoverable.next({ reason: 'bad hash' });
    unrecoverable.next({ reason: 'bad hash' });
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('does nothing when the service worker is disabled', async () => {
    setup(false);
    versions.next({ type: 'VERSION_READY' });
    routerEvents.next(new NavigationStart(1, '/groups'));
    unrecoverable.next({});
    await flush();
    expect(reload).not.toHaveBeenCalled();
  });
});
