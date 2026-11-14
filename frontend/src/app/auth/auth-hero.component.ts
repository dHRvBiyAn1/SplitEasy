import { Component } from '@angular/core';
import { TranslatePipe } from '../core/i18n/translate.pipe';

/** Editorial left panel shared by the login and register screens (the "Evenly" hero). */
@Component({
  selector: 'app-auth-hero',
  imports: [TranslatePipe],
  template: `
    <div class="auth-hero__top">
      <span class="dc-split-mark" aria-hidden="true"></span>
      <span class="auth-hero__wordmark">{{ 'common.spliteasy' | t }}</span>
    </div>

    <div class="auth-hero__body">
      <p class="dc-eyebrow">{{ 'authHero.sharedExpensesSettled' | t }}</p>
      <h1 class="dc-display auth-hero__headline">
        {{ 'authHero.goodPlans' | t }}<br /><em>{{ 'common.goodCompany' | t }}</em>
      </h1>
      <p class="auth-hero__lede">
        {{ 'authHero.trackWhoPaidWhat' | t }}
      </p>

      <figure class="auth-hero__sample dc-card">
        <div class="auth-hero__sample-head">
          <div>
            <div class="auth-hero__sample-title">{{ 'authHero.dinnerAtRamiro' | t }}</div>
            <div class="auth-hero__sample-meta">{{ 'authHero.lisbonTripPaidBy' | t }}</div>
          </div>
          <div class="auth-hero__sample-amount">$186.30</div>
        </div>
        <figcaption class="auth-hero__sample-split">
          <span>{{ 'authHero.split3WaysEqually' | t }}</span>
          <span class="auth-hero__sample-back">{{ 'authHero.12420BackTo' | t }}</span>
        </figcaption>
      </figure>
    </div>

    <p class="auth-hero__foot">{{ 'authHero.smallTabsClearHeads' | t }}</p>
  `,
  styleUrl: './auth.scss',
})
export class AuthHeroComponent {}
