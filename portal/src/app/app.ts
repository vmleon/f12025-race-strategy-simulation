import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { HealthService } from './health.service';

@Component({
  selector: 'app-root',
  template: `
    <h1>Telemetry Portal</h1>

    <section>
      <h2>Backend Health</h2>
      <p>Status: {{ healthStatus() }}</p>
    </section>

    <section>
      <h2>WebSocket Heartbeat</h2>
      <p>Last beat: {{ lastHeartbeat() }}</p>
    </section>
  `,
  styleUrl: './app.css'
})
export class App implements OnInit, OnDestroy {
  healthStatus = signal('loading...');
  lastHeartbeat = signal('waiting...');

  private heartbeatSub?: Subscription;

  constructor(private healthService: HealthService) {}

  ngOnInit() {
    this.healthService.getHealth().subscribe({
      next: (data) => this.healthStatus.set(data.status),
      error: () => this.healthStatus.set('DOWN'),
    });

    this.heartbeatSub = this.healthService.heartbeat$.subscribe({
      next: (msg) => this.lastHeartbeat.set(new Date(msg.ts).toLocaleTimeString()),
      error: () => this.lastHeartbeat.set('disconnected'),
    });
  }

  ngOnDestroy() {
    this.heartbeatSub?.unsubscribe();
  }
}
