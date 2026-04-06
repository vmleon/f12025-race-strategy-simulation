import { Component, Input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { CarSnapshot } from '../../race.service';

// F1 UDP wheel order: [RL, RR, FL, FR]
const CORNERS = [
  { label: 'FL', idx: 2 },
  { label: 'FR', idx: 3 },
  { label: 'RL', idx: 0 },
  { label: 'RR', idx: 1 },
];

const WEAR_YELLOW = 25;
const WEAR_RED = 50;

const TYRE_COLD = 70;
const TYRE_OPTIMAL_MIN = 80;
const TYRE_OPTIMAL_MAX = 110;
const TYRE_HOT = 120;

const BRAKE_COLD = 100;
const BRAKE_OPTIMAL_MIN = 200;
const BRAKE_OPTIMAL_MAX = 800;
const BRAKE_HOT = 1000;

const DARK_BLUE = '#1565c0';
const LIGHT_BLUE = '#42a5f5';
const GREEN = '#4caf50';
const YELLOW = '#ffc107';
const RED = '#ef5350';

@Component({
  selector: 'app-tyres-panel',
  template: `
    <div class="panel">
      <h3>Tyres & Brakes</h3>
      @if (car && car.tyreWear) {
        <div class="tyre-grid">
          @for (c of corners; track c.label) {
            <div class="corner">
              <span class="corner-label">{{ c.label }}</span>
              <div class="metrics">
                <div class="metric">
                  <span class="metric-label">Wear</span>
                  <span class="metric-value" [style.color]="wearColour(car.tyreWear![c.idx])">
                    {{ car.tyreWear![c.idx] | number: '1.0-0' }}%
                  </span>
                </div>
                <div class="metric">
                  <span class="metric-label">Surf</span>
                  <span
                    class="metric-value"
                    [style.color]="tempColour(car.tyreSurfTemp?.[c.idx] ?? 0)"
                  >
                    {{ car.tyreSurfTemp?.[c.idx] ?? '-' }}°
                  </span>
                </div>
                <div class="metric">
                  <span class="metric-label">Inner</span>
                  <span
                    class="metric-value"
                    [style.color]="tempColour(car.tyreInnerTemp?.[c.idx] ?? 0)"
                  >
                    {{ car.tyreInnerTemp?.[c.idx] ?? '-' }}°
                  </span>
                </div>
                <div class="metric">
                  <span class="metric-label">Brake</span>
                  <span
                    class="metric-value"
                    [style.color]="brakeColour(car.brakeTemp?.[c.idx] ?? 0)"
                  >
                    {{ car.brakeTemp?.[c.idx] ?? '-' }}°
                  </span>
                </div>
              </div>
            </div>
          }
        </div>
      } @else {
        <p class="empty">No data</p>
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
    .tyre-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.5rem;
    }
    .corner {
      background: #222;
      border-radius: 6px;
      padding: 0.5rem;
    }
    .corner-label {
      font-weight: bold;
      font-size: 0.9rem;
      color: #e0e0e0;
      display: block;
      margin-bottom: 0.25rem;
    }
    .metrics {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.2rem 0.5rem;
    }
    .metric {
      display: flex;
      justify-content: space-between;
      font-size: 0.8rem;
    }
    .metric-label {
      color: #888;
    }
    .metric-value {
      font-weight: bold;
      font-family: monospace;
    }
    .empty {
      color: #888;
      margin: 0;
      font-size: 0.85rem;
    }
  `,
  imports: [DecimalPipe],
})
export class TyresPanelComponent {
  @Input() car: CarSnapshot | null = null;

  readonly corners = CORNERS;

  wearColour(value: number): string {
    if (value < WEAR_YELLOW) return GREEN;
    if (value < WEAR_RED) return YELLOW;
    return RED;
  }

  bandColour(value: number, cold: number, optMin: number, optMax: number, hot: number): string {
    if (value < cold) return DARK_BLUE;
    if (value < optMin) return LIGHT_BLUE;
    if (value <= optMax) return GREEN;
    if (value <= hot) return YELLOW;
    return RED;
  }

  tempColour(value: number): string {
    return this.bandColour(value, TYRE_COLD, TYRE_OPTIMAL_MIN, TYRE_OPTIMAL_MAX, TYRE_HOT);
  }

  brakeColour(value: number): string {
    return this.bandColour(value, BRAKE_COLD, BRAKE_OPTIMAL_MIN, BRAKE_OPTIMAL_MAX, BRAKE_HOT);
  }
}
