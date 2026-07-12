import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { concatMap, from, of } from 'rxjs';
import { catchError, toArray } from 'rxjs/operators';
import { DashboardService } from '../dashboard/dashboard.service';
import { GroupType } from '../dashboard/dashboard.models';
import { GroupService } from '../groups/group.service';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';

const TYPES: { value: GroupType; label: string }[] = [
  { value: 'HOME', label: 'Home' },
  { value: 'TRIP', label: 'Trip' },
  { value: 'DINING', label: 'Dining' },
  { value: 'EVENT', label: 'Event' },
  { value: 'OTHER', label: 'Other' },
];

@Component({
  selector: 'app-new-group-modal',
  imports: [ReactiveFormsModule, ModalShellComponent],
  templateUrl: './new-group-modal.component.html',
  styleUrl: './modals.scss',
})
export class NewGroupModalComponent {
  private readonly groups = inject(GroupService);
  private readonly dashboard = inject(DashboardService);
  private readonly modal = inject(ModalService);
  private readonly router = inject(Router);

  protected readonly types = TYPES;
  protected readonly name = new FormControl('', { nonNullable: true, validators: [Validators.required] });
  protected readonly type = signal<GroupType>('HOME');
  protected readonly emails = signal<string[]>([]);
  protected readonly emailInput = new FormControl('', { nonNullable: true });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  addEmail(): void {
    const e = this.emailInput.value.trim().toLowerCase();
    if (e && !this.emails().includes(e)) {
      this.emails.update((l) => [...l, e]);
    }
    this.emailInput.reset();
  }

  removeEmail(e: string): void {
    this.emails.update((l) => l.filter((x) => x !== e));
  }

  close(): void {
    this.modal.close();
  }

  save(): void {
    if (this.name.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.groups.createGroup(this.name.value.trim(), this.type()).subscribe({
      next: (group) => {
        // Add each member by email in order; ignore individual failures (e.g. unknown user).
        from(this.emails())
          .pipe(
            concatMap((email) =>
              this.groups.addMember(group.id, email).pipe(catchError(() => of(null))),
            ),
            toArray(),
          )
          .subscribe(() => {
            this.dashboard.refresh();
            this.modal.close();
            this.router.navigate(['/groups', group.id]);
          });
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not create the group.');
        this.saving.set(false);
      },
    });
  }
}
