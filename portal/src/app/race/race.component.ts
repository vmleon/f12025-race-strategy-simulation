import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { Subscription } from 'rxjs';
import { RaceService, CarSnapshot, RaceMessage } from '../race.service';
import { trackName } from '../track-names';
import { DecimalPipe } from '@angular/common';

@Component({
  selector: 'app-race',
  imports: [DecimalPipe],
  template: `
    <div class="race-header">
      <h2>Live Race</h2>
      <div class="session-info">
        @if (trackId() != null) {
          <span class="badge">{{ trackLabel() }}</span>
        }
        @if (totalLaps()) {
          <span class="badge">Lap {{ currentLap() }} / {{ totalLaps() }}</span>
        }
        @if (weather() != null) {
          <span class="badge">{{ weatherLabel() }}</span>
        }
        @if (safetyCarStatus() && safetyCarStatus()! > 0) {
          <span class="badge safety-car">{{ safetyCarLabel() }}</span>
        }
        <span class="badge" [class.live]="status() === 'live'" [class.off]="status() !== 'live'">
          {{ status() }}
        </span>
      </div>
    </div>

    @if (cars().length > 0) {
      <table class="race-table">
        <thead>
          <tr>
            <th>Pos</th>
            <th>Driver</th>
            <th>Lap</th>
            <th>S1</th>
            <th>S2</th>
            <th>S3</th>
            <th>Tyre</th>
            <th>Age</th>
            <th>Pits</th>
            <th>Pit</th>
            <th>Fuel</th>
            <th>Dmg</th>
          </tr>
        </thead>
        <tbody>
          @for (car of cars(); track car.idx) {
            <tr [class.in-pit]="car.pitStatus !== 0" [class.ai]="car.ai">
              <td class="pos">{{ car.pos }}</td>
              <td class="driver">{{ car.name || 'Car ' + car.idx }}</td>
              <td>{{ car.lap }}</td>
              <td class="sector-time">{{ formatSector(car.lastSectorMs, 0) }}</td>
              <td class="sector-time">{{ formatSector(car.lastSectorMs, 1) }}</td>
              <td class="sector-time">{{ formatSector(car.lastSectorMs, 2) }}</td>
              <td>
                <span class="tyre" [attr.data-tyre]="car.tyre">{{ car.tyre }}</span>
              </td>
              <td>{{ car.tyreAge }}</td>
              <td>{{ car.pits ?? 0 }}</td>
              <td class="pit-status">{{ pitLabel(car.pitStatus) }}</td>
              <td>{{ car.fuel != null ? (car.fuel | number : '1.1-1') + ' kg' : '-' }}</td>
              <td>{{ damageLabel(car) }}</td>
            </tr>
          }
        </tbody>
      </table>
    } @else {
      <p class="empty">No live session. Start the game and telemetry server to see data here.</p>
    }

    @if (events().length > 0) {
      <div class="events">
        <h3>Recent Events</h3>
        @for (ev of events(); track $index) {
          <div class="event-item">
            <span class="event-type">{{ ev.event }}</span>
            @if (ev.carIndex != null) {
              <span>Car {{ ev.carIndex }}</span>
            }
          </div>
        }
      </div>
    }
  `,
  styles: `
    .race-header {
      display: flex;
      align-items: center;
      gap: 1rem;
      margin-bottom: 1rem;
    }
    .race-header h2 { margin: 0; }
    .session-info { display: flex; gap: 0.5rem; flex-wrap: wrap; }
    .badge {
      font-size: 0.8rem;
      padding: 0.2rem 0.5rem;
      border-radius: 4px;
      background: #333;
      color: #ccc;
    }
    .badge.live { background: #1b5e20; color: #a5d6a7; }
    .badge.off { background: #555; }
    .badge.safety-car { background: #f9a825; color: #000; }

    .race-table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    .race-table th {
      text-align: left;
      padding: 0.4rem 0.6rem;
      border-bottom: 2px solid #444;
      color: #999;
      font-size: 0.8rem;
      text-transform: uppercase;
    }
    .race-table td { padding: 0.35rem 0.6rem; border-bottom: 1px solid #2a2a2a; }
    .race-table tr.in-pit { background: #2a1a00; }
    .race-table tr.ai { opacity: 0.7; }
    .pos { font-weight: bold; min-width: 2rem; }
    .driver { white-space: nowrap; }
    .sector-time { font-family: monospace; font-size: 0.85rem; }
    .pit-status { font-weight: bold; }

    .tyre { padding: 0.15rem 0.4rem; border-radius: 3px; font-size: 0.8rem; font-weight: bold; }
    .tyre[data-tyre='S'] { background: #e10600; color: #fff; }
    .tyre[data-tyre='M'] { background: #ffd600; color: #000; }
    .tyre[data-tyre='H'] { background: #eee; color: #000; }
    .tyre[data-tyre='I'] { background: #43a047; color: #fff; }
    .tyre[data-tyre='W'] { background: #1565c0; color: #fff; }

    .events { margin-top: 1.5rem; }
    .events h3 { margin-bottom: 0.5rem; }
    .event-item { display: flex; gap: 0.5rem; padding: 0.25rem 0; font-size: 0.85rem; }
    .event-type { font-weight: bold; color: #f9a825; }
    .empty { color: #888; }
  `,
})
export class RaceComponent implements OnInit, OnDestroy {
  status = signal('waiting...');
  trackId = signal<number | null>(null);
  totalLaps = signal(0);
  currentLap = signal(0);
  weather = signal<number | null>(null);
  safetyCarStatus = signal<number | null>(null);
  cars = signal<CarSnapshot[]>([]);
  events = signal<RaceMessage[]>([]);

  trackLabel = computed(() => trackName(this.trackId()!));
  weatherLabel = computed(() => {
    const w = this.weather();
    if (w == null) return '';
    const labels = ['Clear', 'Light Cloud', 'Overcast', 'Light Rain', 'Heavy Rain', 'Storm'];
    return labels[w] ?? `Weather ${w}`;
  });
  safetyCarLabel = computed(() => {
    const s = this.safetyCarStatus();
    if (s === 1) return 'Full SC';
    if (s === 2) return 'VSC';
    if (s === 3) return 'Formation Lap';
    return 'SC';
  });

  private sub?: Subscription;

  constructor(private raceService: RaceService) {}

  ngOnInit() {
    this.sub = this.raceService.race$.subscribe({
      next: (msg) => this.onMessage(msg),
      error: () => this.status.set('disconnected'),
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
  }

  formatSector(sectors: number[] | undefined, idx: number): string {
    if (!sectors || sectors[idx] == null || sectors[idx] === 0) return '-';
    const ms = sectors[idx];
    const s = ms / 1000;
    return s.toFixed(3);
  }

  pitLabel(status: number): string {
    if (status === 1) return 'PIT LANE';
    if (status === 2) return 'PITTING';
    return '-';
  }

  damageLabel(car: CarSnapshot): string {
    const parts: string[] = [];
    if (car.fwDmg && car.fwDmg > 0) parts.push(`FW:${car.fwDmg}`);
    if (car.flDmg && car.flDmg > 0) parts.push(`FL:${car.flDmg}`);
    if (car.engDmg && car.engDmg > 0) parts.push(`E:${car.engDmg}`);
    return parts.length > 0 ? parts.join(' ') : '-';
  }

  private onMessage(msg: RaceMessage) {
    switch (msg.type) {
      case 'state':
        this.status.set('live');
        if (msg.trackId != null) this.trackId.set(msg.trackId);
        if (msg.totalLaps != null) this.totalLaps.set(msg.totalLaps);
        if (msg.currentLap != null) this.currentLap.set(msg.currentLap);
        if (msg.weather != null) this.weather.set(msg.weather);
        if (msg.safetyCarStatus != null) this.safetyCarStatus.set(msg.safetyCarStatus);
        if (msg.cars) {
          this.cars.set([...msg.cars].sort((a, b) => a.pos - b.pos));
        }
        break;
      case 'sessionStarted':
        this.status.set('session started');
        this.cars.set([]);
        this.events.set([]);
        if (msg.trackId != null) this.trackId.set(msg.trackId);
        break;
      case 'sessionEnded':
        this.status.set('session ended');
        break;
      case 'event':
        this.events.update((evs) => [msg, ...evs].slice(0, 10));
        break;
    }
  }
}
