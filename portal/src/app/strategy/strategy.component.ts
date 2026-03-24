import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { Subscription } from 'rxjs';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RaceService, RaceMessage, SimulationResult, SimulationCarResult } from '../race.service';
import { SimulationService } from '../simulation.service';

@Component({
  selector: 'app-strategy',
  imports: [DecimalPipe, PercentPipe],
  template: `
    <div class="header">
      <h2>Strategy Comparison</h2>
      <button (click)="triggerSimulation()" [disabled]="triggering()">
        {{ triggering() ? 'Running...' : 'Run Simulation' }}
      </button>
      @if (error()) {
        <span class="error">{{ error() }}</span>
      }
    </div>

    @if (result()) {
      <div class="meta">
        <span>{{ result()!.iterations | number }} iterations</span>
        <span>{{ result()!.converged ? 'Converged' : 'Not converged' }}</span>
        <span>{{ result()!.wallClockMs | number }} ms</span>
      </div>

      <table class="results-table">
        <thead>
          <tr>
            <th>Pos</th>
            <th>Driver</th>
            <th>Mean Pos</th>
            <th>95% CI</th>
            <th>P(Podium)</th>
            <th>P(Points)</th>
            <th>P(DNF)</th>
            <th>Distribution</th>
          </tr>
        </thead>
        <tbody>
          @for (car of sortedCars(); track car.carIndex) {
            <tr [class.highlight]="$index === 0">
              <td>{{ $index + 1 }}</td>
              <td>{{ car.driverName }}</td>
              <td>{{ car.meanPosition | number : '1.1-1' }}</td>
              <td class="mono">{{ car.ci95Low | number : '1.1-1' }} – {{ car.ci95High | number : '1.1-1' }}</td>
              <td>{{ car.top3Probability | percent : '1.1-1' }}</td>
              <td>{{ car.pointsFinishProbability | percent : '1.1-1' }}</td>
              <td>{{ car.dnfProbability | percent : '1.1-1' }}</td>
              <td class="dist-cell">
                <div class="dist-bars">
                  @for (p of getTopPositions(car); track p.pos) {
                    <div
                      class="bar"
                      [style.height.%]="p.pct * 100"
                      [title]="'P' + p.pos + ': ' + (p.pct * 100).toFixed(1) + '%'"
                    >
                      <span class="bar-label">{{ p.pos }}</span>
                    </div>
                  }
                </div>
              </td>
            </tr>
          }
        </tbody>
      </table>
    } @else {
      <p class="empty">
        No simulation results yet. Results appear automatically during a live session,
        or click "Run Simulation" to trigger one manually.
      </p>
    }
  `,
  styles: `
    .header { display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem; }
    .header h2 { margin: 0; }
    .header button {
      padding: 0.4rem 1rem;
      background: #e10600;
      color: #fff;
      border: none;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.85rem;
    }
    .header button:disabled { opacity: 0.5; cursor: not-allowed; }
    .error { color: #ef5350; font-size: 0.85rem; }

    .meta { display: flex; gap: 1rem; margin-bottom: 1rem; font-size: 0.8rem; color: #999; }

    .results-table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    .results-table th {
      text-align: left;
      padding: 0.4rem 0.6rem;
      border-bottom: 2px solid #444;
      color: #999;
      font-size: 0.8rem;
      text-transform: uppercase;
    }
    .results-table td { padding: 0.35rem 0.6rem; border-bottom: 1px solid #2a2a2a; }
    .results-table tr.highlight { background: #1a2a1a; }
    .mono { font-family: monospace; }

    .dist-cell { width: 200px; }
    .dist-bars {
      display: flex;
      align-items: flex-end;
      gap: 1px;
      height: 30px;
    }
    .bar {
      flex: 1;
      background: #e10600;
      min-width: 3px;
      max-width: 12px;
      border-radius: 1px 1px 0 0;
      position: relative;
    }
    .bar-label {
      position: absolute;
      bottom: -14px;
      left: 50%;
      transform: translateX(-50%);
      font-size: 0.6rem;
      color: #888;
    }
    .empty { color: #888; }
  `,
})
export class StrategyComponent implements OnInit, OnDestroy {
  result = signal<SimulationResult | null>(null);
  triggering = signal(false);
  error = signal('');

  sortedCars = computed(() => {
    const r = this.result();
    if (!r) return [];
    return [...r.cars].sort((a, b) => a.meanPosition - b.meanPosition);
  });

  private sub?: Subscription;

  constructor(
    private raceService: RaceService,
    private simulationService: SimulationService
  ) {}

  ngOnInit() {
    this.sub = this.raceService.race$.subscribe({
      next: (msg) => this.onMessage(msg),
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
  }

  triggerSimulation() {
    this.triggering.set(true);
    this.error.set('');
    this.simulationService.trigger().subscribe({
      next: () => this.triggering.set(false),
      error: (err) => {
        this.triggering.set(false);
        this.error.set(err.error?.message || err.message || 'Failed to trigger simulation');
      },
    });
  }

  getTopPositions(car: SimulationCarResult): { pos: number; pct: number }[] {
    const dist = car.positionDistribution;
    if (!dist) return [];
    return Object.entries(dist)
      .map(([pos, pct]) => ({ pos: +pos, pct }))
      .sort((a, b) => a.pos - b.pos)
      .slice(0, 10);
  }

  private onMessage(msg: RaceMessage) {
    if (msg.type === 'simulationResult' && msg.result) {
      this.result.set(msg.result);
      this.triggering.set(false);
    }
  }
}
