import { AbstractControl, Validators } from '@angular/forms';

export const passwordBytes = (control: AbstractControl) =>
  new TextEncoder().encode(control.value).length > 72 ? { maxBytes: true } : null;
export const newPasswordValidators = [Validators.required, Validators.minLength(8), passwordBytes];
