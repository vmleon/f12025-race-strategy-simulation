import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BaseChartDirective } from 'ng2-charts';
import { ChartConfiguration } from 'chart.js';
import { Subscription, interval } from 'rxjs';
import {
  SystemService,
  SimulationStats,
  RadioStats,
  CalibrationStats,
  DayCount,
  LiveStats,
} from './system.service';
import { ReadinessSectionComponent } from './readiness-section.component';
import { ReadinessScatterComponent } from './readiness-scatter.component';

@Component({
  selector: 'app-system',
  standalone: true,
  imports: [CommonModule, BaseChartDirective, ReadinessSectionComponent, ReadinessScatterComponent],
  template: `
    <section class="system">
      <header class="system__head">
        <h2>System</h2>
      </header>

      <div class="live-strip">
        <div class="tile"><span>{{ live()?.simsInFlight ?? 0 }}</span>in flight</div>
        <div class="tile"><span>{{ live()?.today?.simulations ?? 0 }}</span>sims today</div>
        <div class="tile"><span>{{ live()?.today?.radioMessages ?? 0 }}</span>radio today</div>
        <div class="tile" *ngIf="(live()?.accuracy?.races ?? 0) > 0">
          <span>{{ live()?.accuracy?.meanAbsError ?? 0 | number: '1.1-1' }}</span>sim error (Δpos, last {{ live()?.accuracy?.races }})
        </div>
      </div>

      <!-- Calibration/strategy signal up top: degradation charts beside readiness. -->
      <div class="layout">
        <app-readiness-scatter [trackId]="readinessTrackId"></app-readiness-scatter>
        <app-readiness-section (trackChange)="readinessTrackId = $event"></app-readiness-section>
      </div>

      <!-- Operational counters demoted to a full-width strip at the bottom. -->
      <div class="cards">
        <!-- Simulations -->
        <div class="card">
          <h3>Simulations</h3>
          <div class="tiles">
            <div class="tile"><span>{{ sim()?.total ?? 0 }}</span>total</div>
            <div class="tile"><span>{{ (sim()?.avgDurationMs ?? 0) | number: '1.0-0' }}</span>avg ms</div>
            <div class="tile"><span>{{ (sim()?.avgIterations ?? 0) | number: '1.0-0' }}</span>avg iters</div>
          </div>
          <canvas baseChart type="bar" [data]="simPerDay()" [options]="barOpts"></canvas>
        </div>

        <!-- Simulation status -->
        <div class="card">
          <h3>Run status</h3>
          <div class="tiles">
            <div class="tile"><span>{{ completedRuns }}</span>completed</div>
            <div class="tile"><span>{{ failedRuns }}</span>failed</div>
          </div>
        </div>

        <!-- Radio -->
        <div class="card">
          <h3>Radio messages</h3>
          <div class="tiles"><div class="tile"><span>{{ radio()?.total ?? 0 }}</span>total</div></div>
          <canvas baseChart type="line" [data]="radioPerDay()" [options]="lineOpts"></canvas>
        </div>

        <div class="card">
          <h3>By priority</h3>
          <canvas baseChart type="doughnut" [data]="radioPriority()" [options]="pieOpts"></canvas>
        </div>

        <div class="card">
          <h3>Rendered vs fallback</h3>
          <canvas baseChart type="doughnut" [data]="radioRendered()" [options]="pieOpts"></canvas>
        </div>

        <!-- Calibration -->
        <div class="card">
          <h3>Calibration</h3>
          <div class="tiles">
            <div class="tile"><span>{{ calib()?.totalRuns ?? 0 }}</span>runs</div>
            <div class="tile"><span>{{ calib()?.totalCoefficients ?? 0 }}</span>coeffs</div>
          </div>
          <canvas baseChart type="line" [data]="calibPerDay()" [options]="lineOpts"></canvas>
        </div>
      </div>
    </section>
  `,
  styles: [
    `
      .system { padding: 1.5rem; }
      .system__head { display: flex; justify-content: space-between; align-items: center; }
      .live-strip { display: flex; gap: 1.5rem; margin: 1rem 0; padding: 0.75rem 1rem; background: var(--surface, #1b1b1b); border-radius: 8px; }
      .layout { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; align-items: start; margin-top: 1rem; }
      @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } }
      .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 1rem; margin-top: 1rem; }
      .card { background: var(--surface, #1b1b1b); border-radius: 8px; padding: 1rem; }
      .card h3 { margin: 0 0 0.5rem; font-size: 0.95rem; }
      .tiles { display: flex; gap: 1rem; margin-bottom: 0.5rem; }
      .tile { display: flex; flex-direction: column; font-size: 0.7rem; color: var(--gray-500, #999); }
      .tile span { font-size: 1.4rem; font-weight: 600; color: var(--gray-100, #eee); }
      canvas { max-height: 220px; }
    `,
  ],
})
export class SystemComponent implements OnInit, OnDestroy {
  // Signals (not plain fields): the portal is zoneless, so a plain field set inside an
  // HttpClient.subscribe callback would never repaint the view. signal.set() does.
  sim = signal<SimulationStats | undefined>(undefined);
  radio = signal<RadioStats | undefined>(undefined);
  calib = signal<CalibrationStats | undefined>(undefined);
  live = signal<LiveStats | undefined>(undefined);

  /** Track selected in the readiness section; drives the degradation scatter charts. */
  readinessTrackId?: number;

  get completedRuns(): number { return this.sim()?.byStatus?.['completed'] ?? 0; }
  get failedRuns(): number { return this.sim()?.byStatus?.['failed'] ?? 0; }

  private liveSub?: Subscription;
  private pollSub?: Subscription;

  simPerDay = signal(this.emptyDataset('Simulations'));
  radioPerDay = signal(this.emptyDataset('Radio'));
  radioPriority = signal(this.emptyDataset('Priority'));
  radioRendered = signal(this.emptyDataset('Rendered'));
  calibPerDay = signal(this.emptyDataset('Coefficients'));

  readonly barOpts: ChartConfiguration['options'] = { responsive: true, plugins: { legend: { display: false } } };
  readonly lineOpts: ChartConfiguration['options'] = { responsive: true, plugins: { legend: { display: false } } };
  readonly pieOpts: ChartConfiguration['options'] = { responsive: true };

  constructor(private svc: SystemService) {}

  ngOnInit(): void {
    this.refresh();
    this.liveSub = this.svc.live$().subscribe((l) => this.live.set(l));
    // Auto-refresh the stat cards while a session runs (the live strip polls 5s itself).
    this.pollSub = interval(15_000).subscribe(() => this.refresh());
  }

  ngOnDestroy(): void {
    this.liveSub?.unsubscribe();
    this.pollSub?.unsubscribe();
  }

  refresh(): void {
    this.svc.simulations().subscribe((s) => {
      this.sim.set(s);
      this.simPerDay.set(this.fromDayCounts('Simulations', s.perDay));
    });
    this.svc.radio().subscribe((r) => {
      this.radio.set(r);
      this.radioPerDay.set(this.fromDayCounts('Radio', r.perDay));
      this.radioPriority.set({
        labels: r.byPriority.map((p) => p.priority),
        datasets: [{ data: r.byPriority.map((p) => p.count) }],
      });
      this.radioRendered.set(
        this.fromRecord({
          rendered: r.renderedVsFallback.rendered,
          fallback: r.renderedVsFallback.fallback,
        }),
      );
    });
    this.svc.calibration().subscribe((c) => {
      this.calib.set(c);
      this.calibPerDay.set(this.fromDayCounts('Coefficients', c.perDay));
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
