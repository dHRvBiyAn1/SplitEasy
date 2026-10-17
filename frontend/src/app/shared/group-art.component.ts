import { Component, input } from '@angular/core';
import { GroupType } from '../dashboard/dashboard.models';
const ART: Record<GroupType, string> = {
  HOME: 'm13 35 27-22 27 22M23 30v34h34V30M35 64V43h12v21',
  TRIP: 'm8 58 23-35 16 21 9-13 17 27ZM25 32l6-9 10 14M60 12a7 7 0 1 0 0 14 7 7 0 0 0 0-14Z',
  DINING:
    'M65 44a23 18 0 1 0-46 0 23 18 0 1 0 46 0ZM57 44a15 11 0 1 0-30 0 15 11 0 1 0 30 0ZM9 17v47M4 17v19h10V17M73 17v47',
  EVENT: 'M18 25h44v39H18ZM28 25V15m24 10V15M18 37h44M31 46l9 10 12-16',
  OTHER: 'M40 12 68 28v29L40 72 12 57V28ZM12 28l28 15 28-15M40 43v29',
};
@Component({
  selector: 'app-group-art',
  template: '<svg viewBox="0 0 80 80" aria-hidden="true"><path [attr.d]="art[type()]" /></svg>',
  styles:
    ':host{display:grid;place-items:center;width:72px;height:80px;flex:none;border-radius:var(--radius);background:var(--art1);color:var(--feature);box-shadow:inset 0 3px var(--glint),inset 0 -4px color-mix(in srgb,var(--feature) 22%,transparent);transform:rotate(-4deg)}svg{width:100%;height:100%;fill:none;stroke:currentColor;stroke-width:2.6;stroke-linecap:round;stroke-linejoin:round}',
})
export class GroupArtComponent {
  readonly type = input<GroupType>('OTHER');
  protected readonly art = ART;
}
