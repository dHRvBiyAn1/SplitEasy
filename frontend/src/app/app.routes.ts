import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'login',
    loadComponent: () => import('./auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'register',
    loadComponent: () => import('./auth/register.component').then((m) => m.RegisterComponent),
  },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'groups',
    canActivate: [authGuard],
    loadComponent: () => import('./groups/group-list.component').then((m) => m.GroupListComponent),
  },
  {
    path: 'groups/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./groups/group-detail.component').then((m) => m.GroupDetailComponent),
  },
  {
    path: 'profile',
    canActivate: [authGuard],
    loadComponent: () => import('./profile/profile.component').then((m) => m.ProfileComponent),
  },
  { path: '**', redirectTo: 'groups' },
];
