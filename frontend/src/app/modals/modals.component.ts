import { Component, inject } from '@angular/core';
import { ModalService } from './modal.service';
import { NewGroupModalComponent } from './new-group-modal.component';
import { NewExpenseModalComponent } from './new-expense-modal.component';
import { SettleUpModalComponent } from './settle-up-modal.component';

/** Renders whichever global modal is open. Mounted once in the app shell. */
@Component({
  selector: 'app-modals',
  imports: [NewGroupModalComponent, NewExpenseModalComponent, SettleUpModalComponent],
  template: `
    @switch (modal.active()) {
      @case ('group') { <app-new-group-modal /> }
      @case ('expense') { <app-new-expense-modal /> }
      @case ('settle') { <app-settle-up-modal /> }
    }
  `,
})
export class ModalsComponent {
  protected readonly modal = inject(ModalService);
}
