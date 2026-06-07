import { Component, OnInit, inject } from '@angular/core';
import { CarSnapshot } from '../race.service';
import { DecimalPipe } from '@angular/common';
import { CircuitMapComponent } from './circuit-map/circuit-map.component';
import { PenaltiesPanelComponent } from './penalties-panel/penalties-panel.component';
import { DamagePanelComponent } from './damage-panel/damage-panel.component';
import { TyresPanelComponent } from './tyres-panel/tyres-panel.component';
import { WeatherPanelComponent } from './weather-panel/weather-panel.component';
import { StrategyWidgetComponent } from './strategy-widget/strategy-widget.component';
import { GapIndicatorComponent } from './gap-indicator/gap-indicator.component';
import { LiveRaceStore } from './live-race.store';

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
        @if (trackId() != null) {
          <span class="badge">{{ trackLabel() }}</span>
        }
        @if (totalLaps()) {
          <span class="badge">Lap {{ currentLap() }} / {{ totalLaps() }}</span>
        }
        @if (showSessionRemaining() && sessionTimeLeft() != null) {
          <span class="badge">{{ sessionRemainingLabel() }} left</span>
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
                [insufficientCalibration]="strategyInsufficientCalibration()"
                [startingCompound]="playerCar()?.tyre ?? ''"
              />
              @if (lastPlayerLaps().length > 0) {
                <div class="last-laps">
                  <h3>Last Laps</h3>
                  @for (entry of lastPlayerLaps(); track entry.lap) {
                    <div
                      class="lap-entry"
                      [class.fastest]="bestLapOverallMs() != null && entry.timeMs === bestLapOverallMs()"
                    >
                      <span class="lap-num">L{{ entry.lap }}</span>
                      <span class="lap-right">
                        <span class="tyre" [attr.data-tyre]="entry.tyre">{{ entry.tyre }}</span>
                        <span class="lap-time">{{ formatLapTime(entry.timeMs) }}</span>
                      </span>
                    </div>
                  }
                </div>
              }
            </div>
            <div class="table-scroll">
              @if (showSessionRemaining()) {
                <table class="race-table">
                  <thead>
                    <tr>
                      <th>Pos</th>
                      <th>Driver</th>
                      <th>S1</th>
                      <th>S2</th>
                      <th>S3</th>
                      <th>Best Lap</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of bestTimesRows(); track row.carIdx) {
                      <tr [class.ai]="row.ai" [class.out]="row.out">
                        <td class="pos">{{ row.out ? 'OUT' : row.pos }}</td>
                        <td class="driver">{{ row.name }}</td>
                        <td
                          class="sector-time"
                          [class.fastest]="row.bestS1 != null && row.bestS1 === bestS1Ms()"
                          [class.pb]="row.bestS1 != null && row.bestS1 !== bestS1Ms()"
                        >
                          {{ row.bestS1 != null ? formatMs(row.bestS1) : '-' }}
                        </td>
                        <td
                          class="sector-time"
                          [class.fastest]="row.bestS2 != null && row.bestS2 === bestS2Ms()"
                          [class.pb]="row.bestS2 != null && row.bestS2 !== bestS2Ms()"
                        >
                          {{ row.bestS2 != null ? formatMs(row.bestS2) : '-' }}
                        </td>
                        <td
                          class="sector-time"
                          [class.fastest]="row.bestS3 != null && row.bestS3 === bestS3Ms()"
                          [class.pb]="row.bestS3 != null && row.bestS3 !== bestS3Ms()"
                        >
                          {{ row.bestS3 != null ? formatMs(row.bestS3) : '-' }}
                        </td>
                        <td
                          class="sector-time"
                          [class.fastest]="row.bestLap != null && row.bestLap === bestLapOverallMs()"
                          [class.pb]="row.bestLap != null && row.bestLap !== bestLapOverallMs()"
                        >
                          {{ row.bestLap != null ? formatLapTime(row.bestLap) : '-' }}
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              } @else {
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
                        <td class="sector-time" [class.fastest]="isFastestSector(car, 0)">{{ formatSector(car.lastSectorMs, 0) }}</td>
                        <td class="sector-time" [class.fastest]="isFastestSector(car, 1)">{{ formatSector(car.lastSectorMs, 1) }}</td>
                        <td class="sector-time" [class.fastest]="isFastestSector(car, 2)">{{ formatSector(car.lastSectorMs, 2) }}</td>
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
              }
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
          <app-penalties-panel [car]="playerCar()" [events]="penaltyEvents()" [cars]="cars()" />
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
      align-items: center;
      padding: 0.2rem 0.3rem;
      font-size: 0.8rem;
      border-radius: 3px;
    }
    .lap-entry.fastest {
      background: #9c27b0;
      color: #fff;
    }
    .lap-entry.fastest .lap-num,
    .lap-entry.fastest .lap-time {
      color: #fff;
    }
    .lap-right {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    .lap-num {
      color: #999;
    }
    .lap-time {
      font-family: monospace;
      color: #e0e0e0;
    }
    .race-table td.sector-time.fastest {
      background: #9c27b0;
      color: #fff;
    }
    .race-table td.sector-time.pb {
      background: #1b5e20;
      color: #fff;
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
        height: auto;
        overflow: visible;
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
export class RaceComponent implements OnInit {
  private store = inject(LiveRaceStore);

  // Live state lives in LiveRaceStore so it survives navigation. These are aliases
  // to the very same signals, so the template bindings below are unchanged.
  status = this.store.status;
  trackId = this.store.trackId;
  totalLaps = this.store.totalLaps;
  currentLap = this.store.currentLap;
  weather = this.store.weather;
  trackTemp = this.store.trackTemp;
  airTemp = this.store.airTemp;
  safetyCarStatus = this.store.safetyCarStatus;
  trackLength = this.store.trackLength;
  sessionType = this.store.sessionType;
  sessionTimeLeft = this.store.sessionTimeLeft;
  sessionDuration = this.store.sessionDuration;
  yellowSector = this.store.yellowSector;
  cars = this.store.cars;
  events = this.store.events;
  forecast = this.store.forecast;
  selectedSessionUid = this.store.selectedSessionUid;
  strategyStrategies = this.store.strategyStrategies;
  strategyEvaluatedAtLap = this.store.strategyEvaluatedAtLap;
  strategyStale = this.store.strategyStale;
  strategyInsufficientCalibration = this.store.strategyInsufficientCalibration;
  gapAhead = this.store.gapAhead;
  gapBehind = this.store.gapBehind;
  lastPlayerLaps = this.store.lastPlayerLaps;
  bestLapOverallMs = this.store.bestLapOverallMs;
  bestS1Ms = this.store.bestS1Ms;
  bestS2Ms = this.store.bestS2Ms;
  bestS3Ms = this.store.bestS3Ms;
  bestTimesRows = this.store.bestTimesRows;

  playerCar = this.store.playerCar;
  penaltyEvents = this.store.penaltyEvents;
  trackLabel = this.store.trackLabel;
  weatherLabel = this.store.weatherLabel;
  showSessionRemaining = this.store.showSessionRemaining;
  sessionRemainingLabel = this.store.sessionRemainingLabel;
  safetyCarLabel = this.store.safetyCarLabel;

  ngOnInit() {
    this.store.ensureStarted();
  }

  bestLapForCar(idx: number): number | null {
    return this.store.bestLapForCar(idx);
  }

  isFastestSector(car: CarSnapshot, idx: number): boolean {
    return this.store.isFastestSector(car, idx);
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

  formatMs(ms: number): string {
    return (ms / 1000).toFixed(3);
  }

  pitLabel(status: number): string {
    if (status === 1) return 'PIT LANE';
    if (status === 2) return 'PITTING';
    return '-';
  }
}
