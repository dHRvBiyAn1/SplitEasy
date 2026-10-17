import { Component } from '@angular/core';

/** Editorial left panel shared by the login and register screens (the "Evenly" hero). */
@Component({
  selector: 'app-auth-hero',
  template: `
    <div class="auth-hero__top">
      <span class="dc-split-mark" aria-hidden="true"></span>
      <span class="auth-hero__wordmark">SplitEasy</span>
    </div>

    <div class="auth-hero__body">
      <p class="dc-eyebrow">Shared expenses, settled</p>
      <h1 class="dc-display auth-hero__headline">Good plans.<br /><em>Good company.</em></h1>
      <p class="auth-hero__lede">
        Track who paid what across houses, trips and dinner clubs — split equally, by exact amounts
        or by percentages — keep every shared cost clear.
      </p>

      <figure class="auth-hero__sample dc-card">
        <div class="auth-hero__sample-head">
          <div>
            <div class="auth-hero__sample-title">Dinner at Ramiro</div>
            <div class="auth-hero__sample-meta">Lisbon Trip · paid by you</div>
          </div>
          <div class="auth-hero__sample-amount">$186.30</div>
        </div>
        <figcaption class="auth-hero__sample-split">
          <span>Split 3 ways · equally</span>
          <span class="auth-hero__sample-back">+$124.20 back to you</span>
        </figcaption>
      </figure>
    </div>

    <p class="auth-hero__foot">Small tabs. Clear heads.</p>
  `,
  styleUrl: './auth.scss',
})
export class AuthHeroComponent {}
