import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  isDevMode,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withInMemoryScrolling } from '@angular/router';

import { provideServiceWorker } from '@angular/service-worker';

import { PwaUpdateService } from './core/pwa/pwa-update.service';
import { PendingWritesService } from './core/pending-writes.service';
import { routes } from './app.routes';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { authInterceptor } from './core/auth/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideAppInitializer(() => {
      inject(PendingWritesService);
      inject(PwaUpdateService);
      return firstValueFrom(inject(AuthService).initialize());
    }),
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' }),
    ),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
