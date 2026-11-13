import { Pipe, PipeTransform } from '@angular/core';
import { activeLocale, intlLocale } from './i18n.service';

/** Medium date + time in the active language; impure so a language switch re-renders. */
@Pipe({ name: 'ldate', pure: false })
export class LocaleDatePipe implements PipeTransform {
  transform(value: string | number | Date | null | undefined): string {
    if (value == null || value === '') return '';
    return new Intl.DateTimeFormat(intlLocale(activeLocale()), {
      dateStyle: 'medium',
      timeStyle: 'medium',
    }).format(new Date(value));
  }
}
