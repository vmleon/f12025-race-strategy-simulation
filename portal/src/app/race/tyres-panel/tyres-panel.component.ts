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
    if (value < 30) return '#4caf50';
    if (value < 60) return '#ffc107';
    return '#ef5350';
  }

  tempColour(value: number): string {
    if (value >= 80 && value <= 110) return '#4caf50';
    if (value >= 70 && value <= 120) return '#ffc107';
    return '#ef5350';
  }

  brakeColour(value: number): string {
    if (value >= 200 && value <= 800) return '#4caf50';
    if (value >= 100 && value <= 1000) return '#ffc107';
    return '#ef5350';
  }
}
