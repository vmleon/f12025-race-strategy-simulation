import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'race', pathMatch: 'full' },
  { path: 'race', loadComponent: () => import('./race/race.component').then((m) => m.RaceComponent) },
  {
    path: 'system',
    loadComponent: () => import('./system/system.component').then((m) => m.SystemComponent),
  },
];
