import { Component, Input } from '@angular/core';
import { CarSnapshot, RaceMessage } from '../../race.service';

const PENALTY_TYPES: Record<number, string> = {
  0: 'Drive Through',
  1: 'Stop-Go',
  2: 'Grid Penalty',
  3: 'Penalty Reminder',
  4: 'Time Penalty',
  5: 'Warning',
  6: 'Disqualified',
  7: 'Removed (Formation Lap)',
  8: 'Parked (Too Slow)',
  9: 'Tyre Regulations',
  10: 'This Lap Invalidated',
  11: 'This And Next Lap Invalidated',
  12: 'This Lap Invalidated (No Reason)',
  13: 'This And Next Lap Invalidated (No Reason)',
  14: 'This And Previous Lap Invalidated',
  15: 'This And Previous Lap Invalidated (No Reason)',
  16: 'Retired',
  17: 'Black Flag Timer',
};

@Component({
  selector: 'app-penalties-panel',
  template: `
    <div class="panel">
      <h3>Penalties</h3>
      @if (car && (car.pen || car.unservedDT || car.unservedSG || car.warnings)) {
        <div class="penalty-summary">
          @if (car.pen) {
            <div class="stat">
              <span class="label">Time penalty</span>
              <span class="value">+{{ car.pen }}s</span>
            </div>
          }
          @if (car.warnings) {
            <div class="stat">
              <span class="label">Warnings</span>
              <span class="value">{{ car.warnings }}</span>
            </div>
          }
          @if (car.unservedDT) {
            <div class="stat urgent">
              <span class="label">Drive-through</span>
              <span class="value">{{ car.unservedDT }} unserved</span>
            </div>
          }
          @if (car.unservedSG) {
            <div class="stat urgent">
              <span class="label">Stop & Go</span>
              <span class="value">{{ car.unservedSG }} unserved</span>
            </div>
          }
        </div>
      } @else {
        <p class="clean">No penalties</p>
      }
      @if (events.length > 0) {
        <div class="event-list">
          @for (ev of events; track $index) {
            <div class="penalty-event">
              <span class="type">{{ driverName(ev) }} — {{ penaltyLabel(ev) }}</span>
              <span class="detail"
                >Lap {{ ev.lap ?? '?' }}
                @if (penaltyTimeSec(ev) != null) {
                  — {{ penaltyTimeSec(ev) }}s
                }
              </span>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .panel {
      background: #1a1a1a;
      border-radius: 8px;
      padding: 0.75rem 1rem;
    }
    h3 {
      margin: 0 0 0.5rem;
      font-size: 0.9rem;
      color: #999;
      text-transform: uppercase;
    }
    .penalty-summary {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
    }
    .stat {
      display: flex;
      flex-direction: column;
    }
    .stat .label {
      font-size: 0.75rem;
      color: #888;
    }
    .stat .value {
      font-size: 1.1rem;
      font-weight: bold;
      color: #e0e0e0;
    }
    .stat.urgent .value {
      color: #ef5350;
    }
    .clean {
      color: #4caf50;
      margin: 0;
      font-size: 0.85rem;
    }
    .event-list {
      margin-top: 0.5rem;
      border-top: 1px solid #333;
      padding-top: 0.5rem;
    }
    .penalty-event {
      display: flex;
      justify-content: space-between;
      font-size: 0.8rem;
      padding: 0.15rem 0;
    }
    .penalty-event .type {
      color: #f9a825;
    }
    .penalty-event .detail {
      color: #999;
    }
  `,
})
export class PenaltiesPanelComponent {
  @Input() car: CarSnapshot | null = null;
  @Input() events: RaceMessage[] = [];
  @Input() cars: CarSnapshot[] = [];

  penaltyLabel(ev: RaceMessage): string {
    const pt = ev.penaltyType;
    return pt != null ? (PENALTY_TYPES[pt] ?? `Penalty ${pt}`) : 'Penalty';
  }

  /** Resolve the event's car index to a driver name so the player can tell whose
   * penalty/retirement it is (and that it isn't theirs). */
  driverName(ev: RaceMessage): string {
    if (ev.carIndex == null) return 'Car';
    const match = this.cars.find((c) => c.idx === ev.carIndex);
    return match?.name ?? `Car ${ev.carIndex}`;
  }

  /** Penalty time in seconds, or null when there's no meaningful value — 255 is the
   * unset-byte sentinel (0xFF) and retirements carry no penalty time. */
  penaltyTimeSec(ev: RaceMessage): number | null {
    const t = ev.time;
    if (t == null || t <= 0 || t === 255) return null;
    return t;
  }
}
