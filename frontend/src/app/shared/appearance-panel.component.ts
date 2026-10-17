import { Component, inject } from '@angular/core';
import { PALETTES, ThemeService, ThemeChoice } from '../core/theme.service';
import { MotionPreferenceService } from '../core/motion-preference.service';
import { RibbonService } from '../core/ribbon.service';
@Component({
  selector: 'app-appearance-panel',
  template: ` <div class="appearance">
    <fieldset>
      <legend>Color theme</legend>
      <p>Pick your colors. Keep your preferred light or dark mode.</p>
      <div class="themes">
        @for (p of palettes; track p.value) {
          <button
            type="button"
            class="theme-option"
            [class]="'theme-option theme-option--' + p.value"
            [attr.aria-pressed]="theme.palette() === p.value"
            (click)="theme.setPalette(p.value)"
          >
            <span class="theme-swatches" aria-hidden="true"><i></i><i></i><i></i></span
            ><span>{{ p.label }}</span
            ><span class="theme-check" aria-hidden="true">{{
              theme.palette() === p.value ? '✓' : ''
            }}</span>
          </button>
        }
      </div>
    </fieldset>
    <fieldset>
      <legend>Display mode</legend>
      <div class="dc-segment" role="group" aria-label="Display mode">
        @for (m of modes; track m.value) {
          <button
            type="button"
            [attr.aria-pressed]="theme.choice() === m.value"
            (click)="theme.set(m.value)"
          >
            {{ m.label }}
          </button>
        }
      </div>
    </fieldset>
    <fieldset>
      <legend>Silk Ribbon</legend>
      <p>A fine ribbon for your mouse. A soft bloom and short trace for your touch.</p>
      <div class="touch-pad" #pad>
        <svg viewBox="0 0 220 70" aria-hidden="true">
          <path d="M25 46c24-47 37 31 68-8s39 16 63-11" />
          <circle cx="163" cy="26" r="9" /></svg
        ><span>{{ motion.reduced() ? 'Motion is reduced' : 'Tap or trace here' }}</span
        ><button
          type="button"
          class="dc-btn-outline"
          [disabled]="motion.reduced()"
          (click)="ribbon.previewTouch(pad)"
        >
          Preview touch
        </button>
      </div>
      <button
        type="button"
        class="motion-choice"
        [attr.aria-pressed]="motion.deviceReduced()"
        (click)="motion.setReduced(!motion.deviceReduced())"
      >
        Reduce motion <span aria-hidden="true">{{ motion.deviceReduced() ? 'On' : 'Off' }}</span>
      </button>
    </fieldset>
    <p class="appearance-note">
      Appearance is saved on this device. System accessibility settings take priority.
    </p>
  </div>`,
  styleUrl: './appearance-panel.component.scss',
})
export class AppearancePanelComponent {
  protected readonly theme = inject(ThemeService);
  protected readonly motion = inject(MotionPreferenceService);
  protected readonly ribbon = inject(RibbonService);
  protected readonly palettes = PALETTES;
  protected readonly modes: { value: ThemeChoice; label: string }[] = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
  ];
}
