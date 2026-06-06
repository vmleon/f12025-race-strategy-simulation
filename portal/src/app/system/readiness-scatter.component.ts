import { Component, Input, OnChanges, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BaseChartDirective } from 'ng2-charts';
import { ChartConfiguration } from 'chart.js';
import { Subscription, interval } from 'rxjs';
import { ReadinessService, SectorScatter } from './readiness.service';

// Official F1 compound colours (matches the strategy widget).
const COMPOUND_COLOR: Record<number, string> = {
  16: '#DA291C', // Soft
  17: '#FFD12E', // Medium
  18: '#F0F0EC', // Hard (near-white)
};
const COMPOUND_LABEL: Record<number, string> = { 16: 'Soft', 17: 'Medium', 18: 'Hard' };

@Component({
  selector: 'app-readiness-scatter',
  standalone: true,
  imports: [CommonModule, BaseChartDirective],
  template: `
    <div class="scatter card">
      <div class="head">
        <h3>Degradation by sector</h3>
        <div class="legend">
          <span class="swatch s"></span>Soft
          <span class="swatch m"></span>Medium
          <span class="swatch h"></span>Hard
          <span class="hint">● used · ○ excluded · ● current · ◆ historical</span>
        </div>
      </div>
      @if (empty()) {
        <p class="empty">No player sector data for this track yet.</p>
      } @else {
        <div class="charts">
          @for (c of charts(); track $index) {
            <div class="chart">
              <h4>Sector {{ $index + 1 }}</h4>
              <canvas baseChart type="scatter" [data]="c" [options]="opts"></canvas>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      .scatter { margin-top: 1rem; background: var(--surface, #1b1b1b); border-radius: 8px; padding: 1rem; }
      .head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem; }
      .head h3 { margin: 0; font-size: 0.95rem; }
      .legend { font-size: 0.72rem; color: var(--gray-500, #999); display: flex; align-items: center; gap: 0.35rem; }
      .swatch { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-left: 0.5rem; }
      .swatch.s { background: #da291c; }
      .swatch.m { background: #ffd12e; }
      .swatch.h { background: #f0f0ec; }
      .legend .hint { margin-left: 0.75rem; color: #777; }
      .charts { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; margin-top: 0.75rem; }
      @media (max-width: 1100px) { .charts { grid-template-columns: 1fr; } }
      .chart { height: 240px; display: flex; flex-direction: column; }
      .chart h4 { margin: 0 0 0.25rem; font-size: 0.8rem; color: var(--gray-500, #999); }
      .chart canvas { flex: 1; min-height: 0; }
      .empty { color: var(--gray-500, #999); font-size: 0.85rem; }
    `,
  ],
})
export class ReadinessScatterComponent implements OnChanges, OnInit, OnDestroy {
  @Input() trackId?: number;

  charts = signal<ChartConfiguration['data'][]>([]);
  empty = signal(true);
  private pollSub?: Subscription;

  readonly opts: ChartConfiguration['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    scales: {
      x: { title: { display: true, text: 'Tyre age (laps)' } },
      y: { title: { display: true, text: 'Sector time (s)' } },
    },
    plugins: { legend: { display: false } },
  };

  constructor(private svc: ReadinessService) {}

  ngOnInit(): void {
    // Follow the readiness section's poll cadence.
    this.pollSub = interval(15_000).subscribe(() => this.refresh());
  }

  ngOnChanges(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  private refresh(): void {
    if (this.trackId == null) return;
    this.svc.scatter(this.trackId).subscribe((res) => {
      this.charts.set(res.sectors.map((s) => this.buildSector(s)));
      this.empty.set(res.sectors.every((s) => s.compounds.every((c) => c.points.length === 0)));
    });
  }

  private buildSector(sector: SectorScatter): ChartConfiguration['data'] {
    // Mixed scatter-point + regression-line datasets; Chart.js's strict mixed-chart
    // generics don't model per-dataset `type` well, so build untyped here.
    const datasets: any[] = [];
    for (const c of sector.compounds) {
      const color = COMPOUND_COLOR[c.compound] ?? '#888888';
      if (c.points.length) {
        datasets.push({
          type: 'scatter',
          label: COMPOUND_LABEL[c.compound],
          data: c.points.map((p) => ({ x: p.age, y: p.timeMs / 1000 })),
          pointStyle: c.points.map((p) => (p.current ? 'circle' : 'rectRot')),
          pointBackgroundColor: c.points.map((p) => (p.used ? color : 'transparent')),
          pointBorderColor: color,
          pointBorderWidth: 1.5,
          pointRadius: 4,
        });
      }
      if (c.regression && c.points.length) {
        const ages = c.points.map((p) => p.age);
        const lo = Math.min(...ages);
        const hi = Math.max(...ages);
        datasets.push({
          type: 'line',
          label: `${COMPOUND_LABEL[c.compound]} fit`,
          data: [
            { x: lo, y: (c.regression.slope * lo + c.regression.intercept) / 1000 },
            { x: hi, y: (c.regression.slope * hi + c.regression.intercept) / 1000 },
          ],
          borderColor: color,
          borderWidth: 2,
          borderDash: [4, 3],
          pointRadius: 0,
          fill: false,
        });
      }
    }
    return { datasets };
  }
}
