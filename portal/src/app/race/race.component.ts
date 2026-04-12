import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { Subscription, interval } from 'rxjs';
import {
  RaceService,
  CarSnapshot,
  RaceMessage,
  WeatherForecastSample,
  RankedStrategy,
} from '../race.service';
import { SessionService, ActiveSessionDto } from '../session.service';
import { trackName } from '../track-names';
import { DecimalPipe } from '@angular/common';
import { CircuitMapComponent } from './circuit-map/circuit-map.component';
import { PenaltiesPanelComponent } from './penalties-panel/penalties-panel.component';
import { DamagePanelComponent } from './damage-panel/damage-panel.component';
import { TyresPanelComponent } from './tyres-panel/tyres-panel.component';
import { WeatherPanelComponent } from './weather-panel/weather-panel.component';
import { StrategyWidgetComponent } from './strategy-widget/strategy-widget.component';
import { GapIndicatorComponent, GapRow } from './gap-indicator/gap-indicator.component';

@Component({
  selector: 'app-race',
  imports: [
    DecimalPipe,
    CircuitMapComponent,
    PenaltiesPanelComponent,
    DamagePanelComponent,
    TyresPanelComponent,
    WeatherPanelComponent,
    StrategyWidgetComponent,
    GapIndicatorComponent,
  ],
  template: `
    <div class="race-header">
      <h2>Live Race</h2>
      <div class="session-info">
        @if (activeSessions().length > 1) {
          <select class="session-select" (change)="onSessionSelected($event)">
            @for (s of activeSessions(); track s.sessionUid) {
              <option [value]="s.sessionUid" [selected]="s.sessionUid === selectedSessionUid()">
                {{ sessionLabel(s) }}
              </option>
            }
          </select>
        }
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
      <div class="race-layout">
        <div class="left-column">
          <div class="race-content">
            <div class="circuit-column">
              <app-circuit-map
                [cars]="cars()"
                [trackLength]="trackLength()"
                [safetyCarStatus]="safetyCarStatus()"
                [yellowSector]="yellowSector()"
              />
              <app-gap-indicator [ahead]="gapAhead()" [behind]="gapBehind()" />
              <app-strategy-widget
                [strategies]="strategyStrategies()"
                [evaluatedAtLap]="strategyEvaluatedAtLap()"
                [stale]="strategyStale()"
              />
              @if (lastPlayerLaps().length > 0) {
                <div class="last-laps">
                  <h3>Last Laps</h3>
                  @for (entry of lastPlayerLaps(); track entry.lap) {
                    <div class="lap-entry">
                      <span class="lap-num">L{{ entry.lap }}</span>
                      <span class="lap-time">{{ formatLapTime(entry.timeMs) }}</span>
                    </div>
                  }
                </div>
              }
            </div>
            <div class="table-scroll">
              <table class="race-table">
                <thead>
                  <tr>
                    <th>Pos</th>
                    <th>Driver</th>
                    <th>Lap</th>
                    <th>S1</th>
                    <th>S2</th>
                    <th>S3</th>
                    <th>Best</th>
                    <th>Tyre</th>
                    <th>Age</th>
                    <th>Pits</th>
                    <th>Pit</th>
                    <th>Fuel</th>
                  </tr>
                </thead>
                <tbody>
                  @for (car of cars(); track car.idx) {
                    <tr [class.in-pit]="car.pitStatus !== 0" [class.ai]="car.ai" [class.out]="(car.resultStatus ?? 2) >= 4">
                      <td class="pos">{{ (car.resultStatus ?? 2) >= 4 ? 'OUT' : car.pos }}</td>
                      <td class="driver">{{ car.name || 'Car ' + car.idx }}</td>
                      <td>{{ car.lap }}</td>
                      <td class="sector-time">{{ formatSector(car.lastSectorMs, 0) }}</td>
                      <td class="sector-time">{{ formatSector(car.lastSectorMs, 1) }}</td>
                      <td class="sector-time">{{ formatSector(car.lastSectorMs, 2) }}</td>
                      <td class="sector-time">{{ bestLapForCar(car.idx) != null ? formatLapTime(bestLapForCar(car.idx)!) : '-' }}</td>
                      <td>
                        <span class="tyre" [attr.data-tyre]="car.tyre">{{ car.tyre }}</span>
                      </td>
                      <td>{{ car.tyreAge }}</td>
                      <td>{{ car.pits ?? 0 }}</td>
                      <td class="pit-status">{{ pitLabel(car.pitStatus) }}</td>
                      <td>{{ car.fuel != null ? (car.fuel | number: '1.1-1') + ' kg' : '-' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>

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
        </div>

        <div class="right-column">
          <app-weather-panel
            [weather]="weather()"
            [trackTemp]="trackTemp()"
            [airTemp]="airTemp()"
            [forecast]="forecast()"
          />
          <app-tyres-panel [car]="playerCar()" />
          <app-damage-panel [car]="playerCar()" />
          <app-penalties-panel [car]="playerCar()" [events]="penaltyEvents()" />
        </div>
      </div>
    } @else {
      <p class="empty">No live session. Start the game and telemetry server to see data here.</p>
    }
  `,
  styles: `
    .race-header {
      display: flex;
      align-items: center;
      gap: 1rem;
      margin-bottom: 1rem;
    }
    .race-header h2 {
      margin: 0;
    }
    .session-info {
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
      align-items: center;
    }
    .badge {
      font-size: 0.8rem;
      padding: 0.2rem 0.5rem;
      border-radius: 4px;
      background: #333;
      color: #ccc;
    }
    .badge.live {
      background: #1b5e20;
      color: #a5d6a7;
    }
    .badge.off {
      background: #555;
    }
    .badge.safety-car {
      background: #f9a825;
      color: #000;
    }

    .session-select {
      font-size: 0.85rem;
      padding: 0.25rem 0.5rem;
      border-radius: 4px;
      background: #222;
      color: #ccc;
      border: 1px solid #555;
    }

    .race-layout {
      display: flex;
      gap: 1rem;
      align-items: flex-start;
      height: calc(100vh - 5rem);
      overflow: hidden;
    }
    .left-column {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      height: 100%;
    }
    .right-column {
      width: 300px;
      flex-shrink: 0;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      height: 100%;
      overflow-y: auto;
    }

    .race-content {
      display: flex;
      gap: 1rem;
      align-items: flex-start;
      flex: 1;
      min-height: 0;
      overflow: hidden;
    }
    .table-scroll {
      flex: 1;
      min-width: 0;
      overflow-y: auto;
      min-height: 0;
    }
    .circuit-column {
      flex-shrink: 0;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      overflow-y: auto;
      min-height: 0;
    }
    .race-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.8rem;
    }
    .race-table th {
      text-align: left;
      padding: 0.3rem 0.4rem;
      border-bottom: 2px solid #444;
      color: #999;
      font-size: 0.75rem;
      text-transform: uppercase;
    }
    .race-table td {
      padding: 0.2rem 0.4rem;
      border-bottom: 1px solid #2a2a2a;
    }
    .race-table tr.in-pit {
      background: #2a1a00;
    }
    .race-table tr.ai {
      opacity: 0.7;
    }
    .race-table tr.out {
      opacity: 0.4;
    }
    .race-table tr.out .pos {
      color: #e53935;
    }
    .pos {
      font-weight: bold;
      min-width: 2rem;
    }
    .driver {
      white-space: nowrap;
    }
    .sector-time {
      font-family: monospace;
      font-size: 0.8rem;
    }
    .pit-status {
      font-weight: bold;
    }

    .tyre {
      padding: 0.15rem 0.4rem;
      border-radius: 3px;
      font-size: 0.8rem;
      font-weight: bold;
    }
    .tyre[data-tyre='S'] {
      background: #e10600;
      color: #fff;
    }
    .tyre[data-tyre='M'] {
      background: #ffd600;
      color: #000;
    }
    .tyre[data-tyre='H'] {
      background: #eee;
      color: #000;
    }
    .tyre[data-tyre='I'] {
      background: #43a047;
      color: #fff;
    }
    .tyre[data-tyre='W'] {
      background: #1565c0;
      color: #fff;
    }

    .last-laps {
      background: #1a1a1a;
      border-radius: 8px;
      padding: 0.75rem 1rem;
      margin-top: 0.75rem;
    }
    .last-laps h3 {
      margin: 0 0 0.4rem;
      font-size: 0.9rem;
      color: #999;
      text-transform: uppercase;
    }
    .lap-entry {
      display: flex;
      justify-content: space-between;
      padding: 0.2rem 0;
      font-size: 0.8rem;
    }
    .lap-num {
      color: #999;
    }
    .lap-time {
      font-family: monospace;
      color: #e0e0e0;
    }

    .events {
      margin-top: 0.5rem;
      max-height: 4rem;
      overflow-y: auto;
      flex-shrink: 0;
    }
    .events h3 {
      margin: 0 0 0.25rem;
      font-size: 0.8rem;
    }
    .event-item {
      display: flex;
      gap: 0.5rem;
      padding: 0.25rem 0;
      font-size: 0.85rem;
    }
    .event-type {
      font-weight: bold;
      color: #f9a825;
    }
    .empty {
      color: #888;
    }

    @media (max-width: 1200px) {
      .race-layout {
        flex-direction: column;
      }
      .right-column {
        width: 100%;
        flex-direction: row;
        flex-wrap: wrap;
      }
      .right-column > * {
        flex: 1;
        min-width: 250px;
      }
    }
    @media (max-width: 768px) {
      .race-content {
        flex-direction: column;
      }
      .circuit-column {
        width: 100%;
        align-items: center;
      }
      .race-table {
        font-size: 0.8rem;
      }
      .race-table th,
      .race-table td {
        padding: 0.25rem 0.35rem;
      }
      .race-header {
        flex-wrap: wrap;
      }
    }
  `,
})
export class RaceComponent implements OnInit, OnDestroy {
  status = signal('waiting...');
  trackId = signal<number | null>(null);
  totalLaps = signal(0);
  currentLap = signal(0);
  weather = signal<number | null>(null);
  trackTemp = signal<number | null>(null);
  airTemp = signal<number | null>(null);
  safetyCarStatus = signal<number | null>(null);
  trackLength = signal(0);
  yellowSector = signal<number | null>(null);
  cars = signal<CarSnapshot[]>([]);
  events = signal<RaceMessage[]>([]);
  forecast = signal<WeatherForecastSample[]>([]);

  activeSessions = signal<ActiveSessionDto[]>([]);
  selectedSessionUid = signal<string | null>(null);

  strategyStrategies = signal<RankedStrategy[]>([]);
  strategyEvaluatedAtLap = signal(0);
  strategyStale = signal(false);

  private bestLapMap = new Map<number, number>();

  bestLapForCar(idx: number): number | null {
    return this.bestLapMap.get(idx) ?? null;
  }

  // Sector history tracking
  private sectorHistory = new Map<number, { lap: number; sector: number; timeMs: number }[]>();
  private prevSectors = new Map<number, number>();
  private prevLaps = new Map<number, number>();

  gapAhead = signal<GapRow | null>(null);
  gapBehind = signal<GapRow | null>(null);
  lastPlayerLaps = signal<{ lap: number; timeMs: number }[]>([]);

  playerCar = computed(() => this.cars().find((c) => !c.ai) ?? null);
  penaltyEvents = computed(() => this.events().filter((e) => e.event === 'PENA'));

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
  private pollSub?: Subscription;

  constructor(
    private raceService: RaceService,
    private sessionService: SessionService,
  ) {}

  ngOnInit() {
    this.fetchActiveSessions();
    this.pollSub = interval(10_000).subscribe(() => this.fetchActiveSessions());

    this.sub = this.raceService.race$.subscribe({
      next: (msg) => this.onMessage(msg),
      error: () => this.status.set('disconnected'),
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    this.pollSub?.unsubscribe();
  }

  sessionLabel(s: ActiveSessionDto): string {
    return `${trackName(s.trackId)} (${s.sessionUid.substring(0, 8)})`;
  }

  onSessionSelected(event: Event) {
    const uid = (event.target as HTMLSelectElement).value;
    this.selectedSessionUid.set(uid);
    this.resetState();
  }

  formatLapTime(ms: number): string {
    const totalSeconds = ms / 1000;
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return minutes > 0
      ? `${minutes}:${seconds.toFixed(3).padStart(6, '0')}`
      : seconds.toFixed(3);
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

  private fetchActiveSessions() {
    this.sessionService.getActiveSessions().subscribe((sessions) => {
      this.activeSessions.set(sessions);
      const selected = this.selectedSessionUid();
      if (sessions.length === 0) {
        this.selectedSessionUid.set(null);
      } else if (!selected || !sessions.some((s) => s.sessionUid === selected)) {
        this.selectedSessionUid.set(sessions[0].sessionUid);
        this.resetState();
      }
    });
  }

  private resetState() {
    this.status.set('waiting...');
    this.cars.set([]);
    this.events.set([]);
    this.trackId.set(null);
    this.totalLaps.set(0);
    this.currentLap.set(0);
    this.weather.set(null);
    this.trackTemp.set(null);
    this.airTemp.set(null);
    this.safetyCarStatus.set(null);
    this.trackLength.set(0);
    this.yellowSector.set(null);
    this.forecast.set([]);
    this.strategyStrategies.set([]);
    this.strategyEvaluatedAtLap.set(0);
    this.strategyStale.set(false);
    this.sectorHistory.clear();
    this.prevSectors.clear();
    this.prevLaps.clear();
    this.gapAhead.set(null);
    this.gapBehind.set(null);
    this.lastPlayerLaps.set([]);
    this.bestLapMap.clear();
  }

  private updateSectorHistory(cars: CarSnapshot[]) {
    for (const car of cars) {
      const prevSector = this.prevSectors.get(car.idx);
      const prevLap = this.prevLaps.get(car.idx);
      this.prevSectors.set(car.idx, car.sector);
      this.prevLaps.set(car.idx, car.lap);

      if (prevSector === undefined || prevLap === undefined) continue;
      if (car.sector === prevSector && car.lap === prevLap) continue;

      let completedSector: number;
      let completedLap: number;
      let timeMs: number;

      if (prevSector === 0 && car.sector === 1) {
        completedSector = 0;
        completedLap = car.lap;
        timeMs = car.lastSectorMs[0];
      } else if (prevSector === 1 && car.sector === 2) {
        completedSector = 1;
        completedLap = car.lap;
        timeMs = car.lastSectorMs[1] - car.lastSectorMs[0];
      } else if (prevSector === 2 && car.sector === 0) {
        completedSector = 2;
        completedLap = prevLap;
        if (car.lastLapTimeMs && car.lastSectorMs[1] > 0) {
          timeMs = car.lastLapTimeMs - car.lastSectorMs[1];
        } else {
          continue;
        }
      } else {
        continue;
      }

      if (timeMs <= 0) continue;

      const history = this.sectorHistory.get(car.idx) ?? [];
      history.push({ lap: completedLap, sector: completedSector, timeMs });
      if (history.length > 6) history.splice(0, history.length - 6);
      this.sectorHistory.set(car.idx, history);
    }
  }

  private updateGapDeltas() {
    const allCars = this.cars();
    const player = allCars.find((c) => !c.ai);
    if (!player) {
      this.gapAhead.set(null);
      this.gapBehind.set(null);
      return;
    }

    const activeCars = allCars.filter((c) => (c.resultStatus ?? 2) < 4);
    const sorted = [...activeCars].sort((a, b) => a.pos - b.pos);
    const playerIdx = sorted.findIndex((c) => c.idx === player.idx);

    const carAhead = playerIdx > 0 ? sorted[playerIdx - 1] : null;
    const carBehind = playerIdx < sorted.length - 1 ? sorted[playerIdx + 1] : null;

    this.gapAhead.set(carAhead ? this.computeGapRow(player, carAhead) : null);
    this.gapBehind.set(carBehind ? this.computeGapRow(player, carBehind) : null);
  }

  private updateBestLaps(cars: CarSnapshot[]) {
    for (const car of cars) {
      if (!car.lastLapTimeMs || car.lastLapTimeMs <= 0) continue;
      const current = this.bestLapMap.get(car.idx);
      if (current === undefined || car.lastLapTimeMs < current) {
        this.bestLapMap.set(car.idx, car.lastLapTimeMs);
      }
    }
  }

  private updatePlayerLapHistory(cars: CarSnapshot[]) {
    const player = cars.find((c) => !c.ai);
    if (!player || !player.lastLapTimeMs || player.lastLapTimeMs <= 0) return;
    if (player.lap < 2) return; // need at least one completed lap

    const completedLap = player.lap - 1;
    const history = this.lastPlayerLaps();
    const lastEntry = history.length > 0 ? history[history.length - 1] : null;
    if (lastEntry && lastEntry.lap >= completedLap) return;

    const updated = [...history, { lap: completedLap, timeMs: player.lastLapTimeMs }];
    if (updated.length > 5) updated.splice(0, updated.length - 5);
    this.lastPlayerLaps.set(updated);
  }

  private computeGapRow(player: CarSnapshot, other: CarSnapshot): GapRow {
    const playerHist = this.sectorHistory.get(player.idx) ?? [];
    const otherHist = this.sectorHistory.get(other.idx) ?? [];

    const otherMap = new Map<string, number>();
    for (const e of otherHist) {
      otherMap.set(`${e.lap}-${e.sector}`, e.timeMs);
    }

    const common: { playerMs: number; otherMs: number }[] = [];
    for (let i = playerHist.length - 1; i >= 0 && common.length < 3; i--) {
      const e = playerHist[i];
      const otherMs = otherMap.get(`${e.lap}-${e.sector}`);
      if (otherMs !== undefined) {
        common.unshift({ playerMs: e.timeMs, otherMs });
      }
    }

    const deltas: (number | null)[] = [];
    for (let i = 0; i < 3; i++) {
      if (i < common.length) {
        deltas.push(common[i].otherMs - common[i].playerMs);
      } else {
        deltas.push(null);
      }
    }

    const code = (other.name ?? `Car ${other.idx}`).substring(0, 3).toUpperCase();
    return { driverCode: code, deltas };
  }

  private onMessage(msg: RaceMessage) {
    if (
      msg.sessionUid &&
      this.selectedSessionUid() &&
      msg.sessionUid !== this.selectedSessionUid()
    ) {
      return;
    }

    switch (msg.type) {
      case 'state':
        this.status.set('live');
        if (msg.trackId != null) this.trackId.set(msg.trackId);
        if (msg.totalLaps != null) this.totalLaps.set(msg.totalLaps);
        if (msg.currentLap != null) this.currentLap.set(msg.currentLap);
        if (msg.weather != null) this.weather.set(msg.weather);
        if (msg.trackTemp != null) this.trackTemp.set(msg.trackTemp);
        if (msg.airTemp != null) this.airTemp.set(msg.airTemp);
        if (msg.safetyCarStatus != null) this.safetyCarStatus.set(msg.safetyCarStatus);
        if (msg.trackLength != null) this.trackLength.set(msg.trackLength);
        if (msg.cars) {
          const active = msg.cars.filter((c) => (c.resultStatus ?? 2) >= 2);
          const racing = active
            .filter((c) => (c.resultStatus ?? 2) < 4)
            .sort((a, b) => a.pos - b.pos);
          const out = active
            .filter((c) => (c.resultStatus ?? 2) >= 4)
            .sort((a, b) => a.pos - b.pos);
          this.cars.set([...racing, ...out]);
          this.updateSectorHistory(msg.cars!);
          this.updateGapDeltas();
          this.updatePlayerLapHistory(msg.cars!);
          this.updateBestLaps(msg.cars!);
        }
        if (msg.forecast) {
          this.forecast.set(msg.forecast);
        }
        break;
      case 'sessionStarted':
        this.status.set('session started');
        this.cars.set([]);
        this.events.set([]);
        this.forecast.set([]);
        this.sectorHistory.clear();
        this.prevSectors.clear();
        this.prevLaps.clear();
        this.gapAhead.set(null);
        this.gapBehind.set(null);
        this.lastPlayerLaps.set([]);
        this.bestLapMap.clear();
        if (msg.trackId != null) this.trackId.set(msg.trackId);
        this.fetchActiveSessions();
        break;
      case 'sessionEnded':
        if (msg.sessionUid === this.selectedSessionUid()) {
          this.status.set('session ended');
          this.fetchActiveSessions();
        }
        break;
      case 'event':
        this.events.update((evs) => [msg, ...evs].slice(0, 10));
        break;
      case 'strategyEvaluation':
        if (msg.evaluation) {
          this.strategyStrategies.set(msg.evaluation.strategies);
          this.strategyEvaluatedAtLap.set(msg.evaluatedAtLap ?? 0);
          this.strategyStale.set(msg.stale ?? false);
        }
        break;
    }
  }
}
