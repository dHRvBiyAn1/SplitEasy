import { Component } from '@angular/core';

/** Editorial left panel shared by the login and register screens (the "Evenly" hero). */
@Component({
  selector: 'app-auth-hero',
  template: `
    <div class="auth-hero__top">
      <span class="auth-hero__mark" aria-hidden="true">=</span>
      <span class="auth-hero__wordmark">SplitEasy</span>
    </div>

    <div class="auth-hero__body">
      <p class="dc-eyebrow">Shared expenses, settled</p>
      <h1 class="dc-display auth-hero__headline">
        Split the bill.<br /><em>Keep the friends.</em>
      </h1>
      <p class="auth-hero__lede">
        Track who paid what across houses, trips and dinner clubs — split equally, by exact
        amounts or by percentages — then settle up in one tap.
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
          <span>Split 4 ways · equally</span>
          <span class="auth-hero__sample-back">+$139.72 back to you</span>
        </figcaption>
      </figure>
    </div>

    <p class="auth-hero__foot">Even splits. Even friendships.</p>
  `,
  styleUrl: './auth.scss',
})
export class AuthHeroComponent {}
