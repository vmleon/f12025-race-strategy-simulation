import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { HealthService } from './health.service';
import { RaceService, RaceSnapshot, CarSnapshot } from './race.service';

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

    <section>
      <h2>Live Race</h2>
      <p>Status: {{ raceStatus() }}</p>
      @if (raceCars().length > 0) {
        <table>
          <thead>
            <tr>
              <th>Pos</th>
              <th>Car</th>
              <th>Lap</th>
              <th>Sector</th>
              <th>Tyre</th>
              <th>Age</th>
              <th>Pit</th>
            </tr>
          </thead>
          <tbody>
            @for (car of raceCars(); track car.idx) {
              <tr>
                <td>{{ car.pos }}</td>
                <td>{{ car.idx }}</td>
                <td>{{ car.lap }}</td>
                <td>{{ car.sector + 1 }}</td>
                <td>{{ car.tyre }}</td>
                <td>{{ car.tyreAge }}</td>
                <td>{{ car.pitStatus === 0 ? '-' : 'PIT' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styleUrl: './app.css'
})
export class App implements OnInit, OnDestroy {
  healthStatus = signal('loading...');
  lastHeartbeat = signal('waiting...');
  raceStatus = signal('waiting...');
  raceCars = signal<CarSnapshot[]>([]);

  private heartbeatSub?: Subscription;
  private raceSub?: Subscription;

  constructor(
    private healthService: HealthService,
    private raceService: RaceService
  ) {}

  ngOnInit() {
    this.healthService.getHealth().subscribe({
      next: (data) => this.healthStatus.set(data.status),
      error: () => this.healthStatus.set('DOWN'),
    });

    this.heartbeatSub = this.healthService.heartbeat$.subscribe({
      next: (msg) => this.lastHeartbeat.set(new Date(msg.ts).toLocaleTimeString()),
      error: () => this.lastHeartbeat.set('disconnected'),
    });

    this.raceSub = this.raceService.race$.subscribe({
      next: (msg) => this.onRaceMessage(msg),
      error: () => this.raceStatus.set('disconnected'),
    });
  }

  ngOnDestroy() {
    this.heartbeatSub?.unsubscribe();
    this.raceSub?.unsubscribe();
  }

  private onRaceMessage(msg: RaceSnapshot) {
    switch (msg.type) {
      case 'state':
        this.raceStatus.set('live');
        if (msg.cars) {
          this.raceCars.set([...msg.cars].sort((a, b) => a.pos - b.pos));
        }
        break;
      case 'sessionStarted':
        this.raceStatus.set('session started');
        this.raceCars.set([]);
        break;
      case 'sessionEnded':
        this.raceStatus.set('session ended');
        break;
    }
  }
}
