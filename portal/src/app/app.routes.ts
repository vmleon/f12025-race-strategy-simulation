import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'race', pathMatch: 'full' },
  { path: 'race', loadComponent: () => import('./race/race.component').then((m) => m.RaceComponent) },
  {
    path: 'strategy',
    loadComponent: () => import('./strategy/strategy.component').then((m) => m.StrategyComponent),
  },
  {
    path: 'calibration',
    loadComponent: () =>
      import('./calibration/calibration.component').then((m) => m.CalibrationComponent),
  },
  {
    path: 'sessions',
    loadComponent: () =>
      import('./sessions/sessions.component').then((m) => m.SessionsComponent),
  },
  {
    path: 'drivers',
    loadComponent: () =>
      import('./drivers/drivers.component').then((m) => m.DriversComponent),
  },
];
