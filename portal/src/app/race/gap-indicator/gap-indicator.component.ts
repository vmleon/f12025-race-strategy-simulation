import { Component, input } from '@angular/core';

export interface GapRow {
  driverCode: string;
  deltas: (number | null)[]; // 3 entries, null = no data
}

@Component({
  selector: 'app-gap-indicator',
  template: `
    @if (ahead() || behind()) {
      <div class="gap-grid">
        <div class="header"></div>
        <div class="header">S1</div>
        <div class="header">S2</div>
        <div class="header">S3</div>

        @if (ahead(); as row) {
          <div class="label ahead">&#9650; {{ row.driverCode }}</div>
          @for (d of row.deltas; track $index) {
            <div class="delta" [class.gain]="d !== null && d < 0" [class.loss]="d !== null && d > 0">
              {{ formatDelta(d) }}
            </div>
          }
        } @else {
          <div class="label ahead">&#9650; ---</div>
          <div class="delta">-</div>
          <div class="delta">-</div>
          <div class="delta">-</div>
        }

        @if (behind(); as row) {
          <div class="label behind">&#9660; {{ row.driverCode }}</div>
          @for (d of row.deltas; track $index) {
            <div class="delta" [class.gain]="d !== null && d < 0" [class.loss]="d !== null && d > 0">
              {{ formatDelta(d) }}
            </div>
          }
        } @else {
          <div class="label behind">&#9660; ---</div>
          <div class="delta">-</div>
          <div class="delta">-</div>
          <div class="delta">-</div>
        }
      </div>
    }
  `,
  styles: `
    .gap-grid {
      display: grid;
      grid-template-columns: 56px 1fr 1fr 1fr;
      gap: 2px 8px;
      align-items: center;
      background: #1a1a2e;
      border-radius: 6px;
      padding: 8px 12px;
      font-family: monospace;
      font-size: 0.85rem;
      margin-top: 0.5rem;
    }
    .header {
      color: #888;
      font-size: 0.7rem;
      text-align: center;
      text-transform: uppercase;
    }
    .label {
      font-size: 0.7rem;
      white-space: nowrap;
    }
    .label.ahead { color: #f59e0b; }
    .label.behind { color: #60a5fa; }
    .delta {
      text-align: center;
      font-weight: bold;
      font-size: 0.9rem;
      color: #888;
    }
    .delta.gain { color: #4ade80; }
    .delta.loss { color: #f87171; }
  `,
})
export class GapIndicatorComponent {
  ahead = input<GapRow | null>(null);
  behind = input<GapRow | null>(null);

  formatDelta(d: number | null): string {
    if (d === null) return '-';
    const sign = d >= 0 ? '+' : '';
    return sign + (d / 1000).toFixed(3);
  }
}
