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
  // The dashboard is the group list now — /groups only exists to keep old links working.
  { path: 'groups', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'groups/:id',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./groups/group-detail.component').then((m) => m.GroupDetailComponent),
  },
  {
    path: 'groups/:id/settings',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./groups/group-settings.component').then((m) => m.GroupSettingsComponent),
  },
  {
    path: 'profile',
    canActivate: [authGuard],
    loadComponent: () => import('./profile/profile.component').then((m) => m.ProfileComponent),
  },
  { path: '**', redirectTo: 'dashboard' },
];
