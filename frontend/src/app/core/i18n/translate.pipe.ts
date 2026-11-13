import { Pipe, PipeTransform, inject } from '@angular/core';
import { I18nService, Params } from './i18n.service';

/** Impure on purpose: a pure pipe would not re-run when the locale signal changes. */
@Pipe({ name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);
  transform(key: string, params?: Params): string {
    return this.i18n.t(key, params);
  }
}
