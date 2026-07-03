import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { GroupService } from './group.service';
import { GroupSummary } from './group.models';

@Component({
  selector: 'app-group-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatListModule,
    MatProgressBarModule,
  ],
  templateUrl: './group-list.component.html',
  styleUrl: './groups.scss',
})
export class GroupListComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly groups = inject(GroupService);

  protected readonly loading = signal(true);
  protected readonly creating = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly myGroups = signal<GroupSummary[]>([]);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
  });

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.groups.listMyGroups().subscribe({
      next: (groups) => {
        this.myGroups.set(groups);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load your groups.');
        this.loading.set(false);
      },
    });
  }

  create(): void {
    if (this.form.invalid || this.creating()) {
      return;
    }
    this.creating.set(true);
    this.error.set(null);
    this.groups.createGroup(this.form.getRawValue().name).subscribe({
      next: (group) => {
        this.myGroups.update((list) => [
          { id: group.id, name: group.name, memberCount: group.members.length },
          ...list,
        ]);
        this.form.reset();
        this.creating.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not create the group.');
        this.creating.set(false);
      },
    });
  }
}
