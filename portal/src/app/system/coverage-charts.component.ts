import { Component, Input, OnChanges, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BaseChartDirective } from 'ng2-charts';
import { ChartConfiguration } from 'chart.js';
import { Subscription, interval } from 'rxjs';
import {
  ReadinessService,
  CoverageCell,
  WearCompound,
  FitConfidence,
  AccuracyPoint,
} from './readiness.service';

const COMPOUND_COLOR: Record<number, string> = {
  16: '#DA291C',
  17: '#FFD12E',
  18: '#F0F0EC',
  7: '#43B02A',
  8: '#0067AD',
};
const COMPOUND_LABEL: Record<number, string> = {
  16: 'Soft',
  17: 'Medium',
  18: 'Hard',
  7: 'Inter',
  8: 'Wet',
};
/** MIN_TYRE_DEG_SAMPLES — at/above this a sector's deg fit is data-backed. */
const GATE = 10;
const THIN = 5;

interface MatrixRow {
  compound: number;
  label: string;
  cells: number[]; // indexed by sector 0,1,2
}
interface MatrixRegime {
  regime: string;
  rows: MatrixRow[];
}

@Component({
  selector: 'app-coverage-charts',
  standalone: true,
  imports: [CommonModule, BaseChartDirective],
  template: `
    <div class="coverage card">
      <h3>Data coverage &amp; calibration confidence</h3>

      <div class="grid">
        <!-- Coverage matrix -->
        <div class="panel">
          <h4>Clean-lap coverage (compound × sector)</h4>
          <div *ngFor="let rg of matrix()" class="matrix">
            <span class="regime">{{ rg.regime }}</span>
            <table>
              <thead>
                <tr><th></th><th>S1</th><th>S2</th><th>S3</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let r of rg.rows">
                  <td class="rl">
                    <span class="chip" [style.background]="color(r.compound)"></span>{{ r.label }}
                  </td>
                  <td *ngFor="let c of r.cells" [class]="cellClass(c)">{{ c }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="empty" *ngIf="matrix().length === 0">No clean laps yet.</p>
          <div class="key">
            <span class="k ok"></span>≥{{ GATE }}
            <span class="k thin"></span>≥{{ THIN }}
            <span class="k low"></span>&lt;{{ THIN }}
          </div>
        </div>

        <!-- Wear-rate scatter -->
        <div class="panel">
          <h4>Tyre wear vs age (80% cliff)</h4>
          <div class="chart"><canvas baseChart type="scatter" [data]="wear()" [options]="wearOpts"></canvas></div>
        </div>

        <!-- Predicted vs actual -->
        <div class="panel">
          <h4>Predicted vs actual finish</h4>
          <div class="chart" *ngIf="hasAccuracy()">
            <canvas baseChart type="scatter" [data]="accuracy()" [options]="accuracyOpts"></canvas>
          </div>
          <p class="empty" *ngIf="!hasAccuracy()">No finished races scored yet.</p>
        </div>

        <!-- Fit confidence -->
        <div class="panel">
          <h4>Fit confidence per knob</h4>
          <table class="fit" *ngIf="fit().length > 0">
            <thead>
              <tr><th>knob</th><th>regime</th><th>sec</th><th>n</th><th>R²</th><th></th></tr>
            </thead>
            <tbody>
              <tr *ngFor="let f of fit()">
                <td>{{ f.knob }}</td>
                <td>{{ f.regime }}</td>
                <td>{{ f.sector == null ? '—' : f.sector + 1 }}</td>
                <td>{{ f.samples ?? '—' }}</td>
                <td>{{ f.rSquared == null ? 'n/a' : (f.rSquared | number: '1.2-2') }}</td>
                <td><span class="flag" *ngIf="f.clamped">prior</span></td>
              </tr>
            </tbody>
          </table>
          <p class="empty" *ngIf="fit().length === 0">No fitted coefficients yet.</p>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .coverage { margin-top: 1rem; background: var(--surface, #1b1b1b); border-radius: 8px; padding: 1rem; }
      .coverage h3 { margin: 0 0 0.75rem; font-size: 0.95rem; }
      .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
      @media (max-width: 1100px) { .grid { grid-template-columns: 1fr; } }
      .panel h4 { margin: 0 0 0.4rem; font-size: 0.8rem; color: var(--gray-500, #999); }
      .matrix { margin-bottom: 0.5rem; }
      .regime { font-size: 0.7rem; color: var(--gray-500, #999); text-transform: uppercase; }
      table { width: 100%; border-collapse: collapse; font-size: 0.78rem; }
      th { text-align: center; color: var(--gray-500, #999); font-weight: 500; padding: 0.2rem; }
      td { text-align: center; padding: 0.25rem; border: 1px solid #2a2a2a; }
      td.rl { text-align: left; white-space: nowrap; }
      .chip { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 0.35rem; vertical-align: middle; }
      td.ok { background: #2e7d32; color: #fff; }
      td.thin { background: #b8860b; color: #fff; }
      td.low { background: #7a2e2e; color: #fff; }
      .key { font-size: 0.7rem; color: var(--gray-500, #999); margin-top: 0.4rem; display: flex; align-items: center; gap: 0.3rem; }
      .k { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-left: 0.5rem; }
      .k.ok { background: #2e7d32; }
      .k.thin { background: #b8860b; }
      .k.low { background: #7a2e2e; }
      .chart { height: 220px; }
      .fit { font-size: 0.74rem; }
      .fit td { border: none; border-bottom: 1px solid #2a2a2a; text-align: left; padding: 0.2rem 0.4rem; }
      .fit th { text-align: left; }
      .flag { font-size: 0.68rem; background: #d9534f; color: #fff; border-radius: 3px; padding: 0 0.3rem; }
      .empty { color: var(--gray-500, #999); font-size: 0.82rem; }
    `,
  ],
})
export class CoverageChartsComponent implements OnChanges, OnInit, OnDestroy {
  @Input() trackId?: number;

  readonly GATE = GATE;
  readonly THIN = THIN;

  matrix = signal<MatrixRegime[]>([]);
  wear = signal<ChartConfiguration['data']>({ datasets: [] });
  accuracy = signal<ChartConfiguration['data']>({ datasets: [] });
  hasAccuracy = signal(false);
  fit = signal<FitConfidence[]>([]);

  private pollSub?: Subscription;

  readonly wearOpts: ChartConfiguration['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    scales: {
      x: { title: { display: true, text: 'Tyre age (laps)' } },
      y: { title: { display: true, text: 'Wear %' }, min: 0, max: 100 },
    },
    plugins: { legend: { display: false } },
  };

  readonly accuracyOpts: ChartConfiguration['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    scales: {
      x: { title: { display: true, text: 'Predicted' } },
      y: { title: { display: true, text: 'Actual' } },
    },
    plugins: { legend: { display: false } },
  };

  constructor(private svc: ReadinessService) {}

  ngOnInit(): void {
    this.pollSub = interval(15_000).subscribe(() => this.refresh());
  }

  ngOnChanges(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  color(compound: number): string {
    return COMPOUND_COLOR[compound] ?? '#888888';
  }

  cellClass(count: number): string {
    if (count >= GATE) return 'ok';
    if (count >= THIN) return 'thin';
    return 'low';
  }

  private refresh(): void {
    if (this.trackId == null) return;
    this.svc.coverage(this.trackId).subscribe((cells) => this.matrix.set(this.buildMatrix(cells)));
    this.svc.wearScatter(this.trackId).subscribe((cs) => this.wear.set(this.buildWear(cs)));
    this.svc.fitConfidence(this.trackId).subscribe((f) => this.fit.set(f));
    this.svc.accuracy().subscribe((pts) => {
      this.hasAccuracy.set(pts.length > 0);
      this.accuracy.set(this.buildAccuracy(pts));
    });
  }

  private buildMatrix(cells: CoverageCell[]): MatrixRegime[] {
    const byRegime = new Map<string, Map<number, number[]>>();
    for (const c of cells) {
      const rg = byRegime.get(c.regime) ?? new Map<number, number[]>();
      const row = rg.get(c.compound) ?? [0, 0, 0];
      if (c.sector >= 0 && c.sector <= 2) row[c.sector] = c.count;
      rg.set(c.compound, row);
      byRegime.set(c.regime, rg);
    }
    const out: MatrixRegime[] = [];
    for (const regime of ['PLAYER', 'AI']) {
      const rg = byRegime.get(regime);
      if (!rg) continue;
      const rows: MatrixRow[] = [];
      for (const compound of [16, 17, 18, 7, 8]) {
        const cells2 = rg.get(compound);
        if (cells2) rows.push({ compound, label: COMPOUND_LABEL[compound] ?? `#${compound}`, cells: cells2 });
      }
      if (rows.length) out.push({ regime, rows });
    }
    return out;
  }

  private buildWear(compounds: WearCompound[]): ChartConfiguration['data'] {
    const datasets: any[] = [];
    let lo = Infinity;
    let hi = -Infinity;
    for (const c of compounds) {
      if (!c.points.length) continue;
      const color = COMPOUND_COLOR[c.compound] ?? '#888888';
      datasets.push({
        type: 'scatter',
        label: COMPOUND_LABEL[c.compound],
        data: c.points.map((p) => ({ x: p.age, y: p.wearPct })),
        pointBackgroundColor: color,
        pointBorderColor: color,
        pointRadius: 3,
      });
      for (const p of c.points) {
        lo = Math.min(lo, p.age);
        hi = Math.max(hi, p.age);
      }
    }
    if (datasets.length) {
      datasets.push({
        type: 'line',
        label: '80% cliff',
        data: [
          { x: lo, y: 80 },
          { x: hi, y: 80 },
        ],
        borderColor: '#d9534f',
        borderWidth: 1.5,
        borderDash: [5, 4],
        pointRadius: 0,
        fill: false,
      });
    }
    return { datasets };
  }

  private buildAccuracy(pts: AccuracyPoint[]): ChartConfiguration['data'] {
    const datasets: any[] = [];
    if (pts.length) {
      datasets.push({
        type: 'scatter',
        label: 'race',
        data: pts.map((p) => ({ x: p.predicted, y: p.actual })),
        pointBackgroundColor: '#4caf50',
        pointBorderColor: '#4caf50',
        pointRadius: 4,
      });
      const all = pts.flatMap((p) => [p.predicted, p.actual]);
      const lo = Math.min(...all);
      const hi = Math.max(...all);
      datasets.push({
        type: 'line',
        label: 'perfect',
        data: [
          { x: lo, y: lo },
          { x: hi, y: hi },
        ],
        borderColor: '#777',
        borderWidth: 1.5,
        borderDash: [5, 4],
        pointRadius: 0,
        fill: false,
      });
    }
    return { datasets };
  }
}
