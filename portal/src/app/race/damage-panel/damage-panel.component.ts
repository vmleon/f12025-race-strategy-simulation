import { Component, Input } from '@angular/core';
import { CarSnapshot } from '../../race.service';

interface DamageZone {
  label: string;
  key: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

const ZONES: DamageZone[] = [
  { label: 'Front Wing', key: 'fwDmg', x: 35, y: 5, w: 50, h: 14 },
  { label: 'Floor', key: 'flDmg', x: 25, y: 36, w: 70, h: 14 },
  { label: 'Sidepod', key: 'spDmg', x: 10, y: 22, w: 80, h: 12 },
  { label: 'Engine', key: 'engDmg', x: 30, y: 52, w: 40, h: 14 },
  { label: 'Gearbox', key: 'gbDmg', x: 35, y: 68, w: 30, h: 12 },
  { label: 'Rear Wing', key: 'rwDmg', x: 35, y: 82, w: 50, h: 14 },
  { label: 'Diffuser', key: 'diffDmg', x: 30, y: 68, w: 40, h: 12 },
];

@Component({
  selector: 'app-damage-panel',
  template: `
    <div class="panel">
      <h3>Car Damage</h3>
      @if (car) {
        <div class="damage-grid">
          @for (zone of zones; track zone.key) {
            <div class="zone" [style.color]="zoneColour(getValue(zone.key))">
              <span class="zone-label">{{ zone.label }}</span>
              <span class="zone-value">{{ getValue(zone.key) }}%</span>
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
    .damage-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.35rem 1rem;
    }
    .zone {
      display: flex;
      justify-content: space-between;
      font-size: 0.85rem;
    }
    .zone-label {
      color: #bbb;
    }
    .zone-value {
      font-weight: bold;
      font-family: monospace;
    }
    .empty {
      color: #888;
      margin: 0;
      font-size: 0.85rem;
    }
  `,
})
export class DamagePanelComponent {
  @Input() car: CarSnapshot | null = null;

  readonly zones = ZONES;

  getValue(key: string): number {
    if (!this.car) return 0;
    return (this.car as unknown as Record<string, number>)[key] ?? 0;
  }

  zoneColour(value: number): string {
    if (value === 0) return '#4caf50';
    if (value < 50) return '#ffc107';
    return '#ef5350';
  }
}
