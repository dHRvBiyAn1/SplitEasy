import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { GroupService } from './group.service';
import { GroupResponse } from './group.models';
import { ExpensePanelComponent } from '../expenses/expense-panel.component';

@Component({
  selector: 'app-group-detail',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatListModule,
    MatProgressBarModule,
    ExpensePanelComponent,
  ],
  templateUrl: './group-detail.component.html',
  styleUrl: './groups.scss',
})
export class GroupDetailComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly groups = inject(GroupService);

  protected readonly loading = signal(true);
  protected readonly adding = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly group = signal<GroupResponse | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });

  private groupId = '';

  ngOnInit(): void {
    this.groupId = this.route.snapshot.paramMap.get('id') ?? '';
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.groups.getGroup(this.groupId).subscribe({
      next: (group) => {
        this.group.set(group);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.status === 403 ? 'You are not a member of this group.' : 'Could not load the group.');
        this.loading.set(false);
      },
    });
  }

  addMember(): void {
    if (this.form.invalid || this.adding()) {
      return;
    }
    this.adding.set(true);
    this.error.set(null);
    this.groups.addMember(this.groupId, this.form.getRawValue().email).subscribe({
      next: (group) => {
        this.group.set(group);
        this.form.reset();
        this.adding.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not add that member.');
        this.adding.set(false);
      },
    });
  }
}
