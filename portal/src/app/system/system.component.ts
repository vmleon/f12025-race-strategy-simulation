import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BaseChartDirective } from 'ng2-charts';
import { ChartConfiguration } from 'chart.js';
import {
  SystemService,
  SimulationStats,
  RadioStats,
  CalibrationStats,
  DayCount,
} from './system.service';

@Component({
  selector: 'app-system',
  standalone: true,
  imports: [CommonModule, BaseChartDirective],
  template: `
    <section class="system">
      <header class="system__head">
        <h2>System</h2>
        <button (click)="refresh()">Refresh</button>
      </header>

      <div class="cards">
        <!-- Simulations -->
        <div class="card">
          <h3>Simulations</h3>
          <div class="tiles">
            <div class="tile"><span>{{ sim?.total ?? 0 }}</span>total</div>
            <div class="tile"><span>{{ (sim?.avgDurationMs ?? 0) | number: '1.0-0' }}</span>avg ms</div>
            <div class="tile"><span>{{ (sim?.avgIterations ?? 0) | number: '1.0-0' }}</span>avg iters</div>
          </div>
          <canvas baseChart type="bar" [data]="simPerDay" [options]="barOpts"></canvas>
        </div>

        <!-- Simulation status -->
        <div class="card">
          <h3>Run status</h3>
          <canvas baseChart type="doughnut" [data]="simStatus" [options]="pieOpts"></canvas>
        </div>

        <!-- Radio -->
        <div class="card">
          <h3>Radio messages</h3>
          <div class="tiles"><div class="tile"><span>{{ radio?.total ?? 0 }}</span>total</div></div>
          <canvas baseChart type="line" [data]="radioPerDay" [options]="lineOpts"></canvas>
        </div>

        <div class="card">
          <h3>By priority</h3>
          <canvas baseChart type="doughnut" [data]="radioPriority" [options]="pieOpts"></canvas>
        </div>

        <div class="card">
          <h3>Rendered vs fallback</h3>
          <canvas baseChart type="doughnut" [data]="radioRendered" [options]="pieOpts"></canvas>
        </div>

        <!-- Calibration -->
        <div class="card">
          <h3>Calibration</h3>
          <div class="tiles">
            <div class="tile"><span>{{ calib?.totalRuns ?? 0 }}</span>runs</div>
            <div class="tile"><span>{{ calib?.totalCoefficients ?? 0 }}</span>coeffs</div>
          </div>
          <canvas baseChart type="line" [data]="calibPerDay" [options]="lineOpts"></canvas>
        </div>
      </div>
    </section>
  `,
  styles: [
    `
      .system { padding: 1.5rem; }
      .system__head { display: flex; justify-content: space-between; align-items: center; }
      .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 1rem; margin-top: 1rem; }
      .card { background: var(--surface, #1b1b1b); border-radius: 8px; padding: 1rem; }
      .card h3 { margin: 0 0 0.5rem; font-size: 0.95rem; }
      .tiles { display: flex; gap: 1rem; margin-bottom: 0.5rem; }
      .tile { display: flex; flex-direction: column; font-size: 0.7rem; color: var(--gray-500, #999); }
      .tile span { font-size: 1.4rem; font-weight: 600; color: var(--gray-100, #eee); }
      canvas { max-height: 220px; }
    `,
  ],
})
export class SystemComponent implements OnInit {
  sim?: SimulationStats;
  radio?: RadioStats;
  calib?: CalibrationStats;

  simPerDay = this.emptyDataset('Simulations');
  simStatus = this.emptyDataset('Status');
  radioPerDay = this.emptyDataset('Radio');
  radioPriority = this.emptyDataset('Priority');
  radioRendered = this.emptyDataset('Rendered');
  calibPerDay = this.emptyDataset('Coefficients');

  readonly barOpts: ChartConfiguration['options'] = { responsive: true, plugins: { legend: { display: false } } };
  readonly lineOpts: ChartConfiguration['options'] = { responsive: true, plugins: { legend: { display: false } } };
  readonly pieOpts: ChartConfiguration['options'] = { responsive: true };

  constructor(private svc: SystemService) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.svc.simulations().subscribe((s) => {
      this.sim = s;
      this.simPerDay = this.fromDayCounts('Simulations', s.perDay);
      this.simStatus = this.fromRecord(s.byStatus);
    });
    this.svc.radio().subscribe((r) => {
      this.radio = r;
      this.radioPerDay = this.fromDayCounts('Radio', r.perDay);
      this.radioPriority = {
        labels: r.byPriority.map((p) => p.priority),
        datasets: [{ data: r.byPriority.map((p) => p.count) }],
      };
      this.radioRendered = this.fromRecord({
        rendered: r.renderedVsFallback.rendered,
        fallback: r.renderedVsFallback.fallback,
      });
    });
    this.svc.calibration().subscribe((c) => {
      this.calib = c;
      this.calibPerDay = this.fromDayCounts('Coefficients', c.perDay);
    });
  }

  private emptyDataset(label: string): ChartConfiguration['data'] {
    return { labels: [], datasets: [{ label, data: [] }] };
  }

  private fromDayCounts(label: string, rows: DayCount[]): ChartConfiguration['data'] {
    return {
      labels: rows.map((r) => r.day),
      datasets: [{ label, data: rows.map((r) => r.count) }],
    };
  }

  private fromRecord(rec: Record<string, number>): ChartConfiguration['data'] {
    return {
      labels: Object.keys(rec),
      datasets: [{ data: Object.values(rec) }],
    };
  }
}
