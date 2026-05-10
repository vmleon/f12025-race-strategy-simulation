import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';
import { HealthService } from './health.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header>
      <h1>F1 Strategy Portal</h1>
      <nav>
        <a routerLink="/race" routerLinkActive="active">Race</a>
        <a routerLink="/strategy" routerLinkActive="active">Strategy</a>
        <a routerLink="/calibration" routerLinkActive="active">Calibration</a>
        <a routerLink="/sessions" routerLinkActive="active">Sessions</a>
        <a routerLink="/drivers" routerLinkActive="active">Drivers</a>
      </nav>
      <span class="status" [class.up]="healthUp()" [class.down]="!healthUp()">
        {{ healthUp() ? 'Backend UP' : 'Backend DOWN' }}
      </span>
    </header>
    <main>
      <router-outlet />
    </main>
  `,
  styleUrl: './app.css',
})
export class App implements OnInit, OnDestroy {
  healthUp = signal(false);

  private heartbeatSub?: Subscription;
  private heartbeatTimeout?: ReturnType<typeof setTimeout>;

  constructor(private healthService: HealthService) {}

  ngOnInit() {
    this.healthService.getHealth().subscribe({
      next: () => this.healthUp.set(true),
      error: () => this.healthUp.set(false),
    });

    this.heartbeatSub = this.healthService.heartbeat$.subscribe({
      next: () => {
        this.healthUp.set(true);
        if (this.heartbeatTimeout) clearTimeout(this.heartbeatTimeout);
        this.heartbeatTimeout = setTimeout(() => this.healthUp.set(false), 15000);
      },
      error: () => this.healthUp.set(false),
    });
  }

  ngOnDestroy() {
    this.heartbeatSub?.unsubscribe();
    if (this.heartbeatTimeout) clearTimeout(this.heartbeatTimeout);
  }
}
