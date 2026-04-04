import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { DecimalPipe, PercentPipe } from '@angular/common';
import {
  RaceService,
  RaceMessage,
  RankedStrategy,
} from '../race.service';

const COMPOUND_NAMES: Record<number, string> = {
  16: 'S',
  17: 'M',
  18: 'H',
  7: 'I',
  8: 'W',
};

const COMPOUND_CLASSES: Record<number, string> = {
  16: 'soft',
  17: 'medium',
  18: 'hard',
  7: 'inter',
  8: 'wet',
};

@Component({
  selector: 'app-strategy',
  imports: [DecimalPipe, PercentPipe],
  template: `
    <div class="header">
      <h2>Strategy Comparison</h2>
      @if (evaluatedAtLap()) {
        <span class="lap-badge" [class.stale]="stale()">
          Lap {{ evaluatedAtLap() }}
          @if (stale()) {
            <span class="stale-indicator">updating...</span>
          }
        </span>
      }
    </div>

    @if (strategies().length > 0) {
      <table class="results-table">
        <thead>
          <tr>
            <th>#</th>
            <th>Strategy</th>
            <th>Stops</th>
            <th>Exp. Pos</th>
            <th>95% CI</th>
            <th>P(Podium)</th>
            <th>P(Points)</th>
            <th>P(DNF)</th>
            <th>Exp. Pts</th>
          </tr>
        </thead>
        <tbody>
          @for (strategy of strategies(); track strategy.rank) {
            <tr [class.top]="strategy.rank === 1">
              <td class="rank">{{ strategy.rank }}</td>
              <td class="label">{{ strategy.candidate.label }}</td>
              <td class="stops">
                @for (stop of strategy.candidate.stops; track stop.onLap) {
                  <span class="stop">
                    L{{ stop.onLap }}
                    <span class="tyre" [attr.data-compound]="compoundClass(stop.newCompound)">
                      {{ compoundName(stop.newCompound) }}
                    </span>
                  </span>
                }
                @if (strategy.candidate.stops.length === 0) {
                  <span class="no-stop">No stop</span>
                }
              </td>
              <td>{{ strategy.meanPosition | number : '1.1-1' }}</td>
              <td class="mono">
                {{ strategy.ci95Low | number : '1.1-1' }} – {{ strategy.ci95High | number : '1.1-1' }}
              </td>
              <td>{{ strategy.top3Probability | percent : '1.1-1' }}</td>
              <td>{{ strategy.pointsFinishProbability | percent : '1.1-1' }}</td>
              <td>{{ strategy.dnfProbability | percent : '1.1-1' }}</td>
              <td class="pts">{{ strategy.expectedPoints | number : '1.1-1' }}</td>
            </tr>
          }
        </tbody>
      </table>
    } @else {
      <p class="empty">
        No strategy evaluation results yet. Results appear automatically during a live race session.
      </p>
    }
  `,
  styles: `
    .header { display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem; }
    .header h2 { margin: 0; }
    .lap-badge {
      font-size: 0.8rem;
      color: #999;
      padding: 0.2rem 0.5rem;
      border: 1px solid #444;
      border-radius: 4px;
    }
    .lap-badge.stale { border-color: #ffa726; color: #ffa726; }
    .stale-indicator { font-style: italic; margin-left: 0.3rem; }

    .results-table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
    .results-table th {
      text-align: left;
      padding: 0.4rem 0.6rem;
      border-bottom: 2px solid #444;
      color: #999;
      font-size: 0.75rem;
      text-transform: uppercase;
    }
    .results-table td { padding: 0.35rem 0.6rem; border-bottom: 1px solid #2a2a2a; }
    .results-table tr.top { background: #1a2a1a; }
    .rank { font-weight: bold; color: #e0e0e0; }
    .label { color: #e0e0e0; white-space: nowrap; }
    .mono { font-family: monospace; }
    .pts { font-weight: bold; }

    .stops { display: flex; gap: 0.4rem; flex-wrap: wrap; }
    .stop {
      display: inline-flex;
      align-items: center;
      gap: 0.2rem;
      font-size: 0.8rem;
      color: #bbb;
    }
    .no-stop { font-size: 0.8rem; color: #888; font-style: italic; }
    .tyre {
      padding: 0.1rem 0.3rem;
      border-radius: 3px;
      font-size: 0.7rem;
      font-weight: bold;
    }
    .tyre[data-compound='soft'] { background: #e10600; color: #fff; }
    .tyre[data-compound='medium'] { background: #ffd600; color: #000; }
    .tyre[data-compound='hard'] { background: #eee; color: #000; }
    .tyre[data-compound='inter'] { background: #43a047; color: #fff; }
    .tyre[data-compound='wet'] { background: #1565c0; color: #fff; }

    .empty { color: #888; }

    @media (max-width: 1200px) {
      .results-table { display: block; overflow-x: auto; }
    }
    @media (max-width: 768px) {
      .results-table { font-size: 0.8rem; }
      .results-table th, .results-table td { padding: 0.25rem 0.35rem; }
      .header { flex-wrap: wrap; }
    }
  `,
})
export class StrategyComponent implements OnInit, OnDestroy {
  evaluatedAtLap = signal(0);
  stale = signal(false);
  strategies = signal<RankedStrategy[]>([]);

  private sub?: Subscription;

  constructor(private raceService: RaceService) {}

  ngOnInit() {
    this.sub = this.raceService.race$.subscribe({
      next: (msg) => this.onMessage(msg),
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
  }

  compoundName(compound: number): string {
    return COMPOUND_NAMES[compound] ?? `C${compound}`;
  }

  compoundClass(compound: number): string {
    return COMPOUND_CLASSES[compound] ?? 'unknown';
  }

  private onMessage(msg: RaceMessage) {
    if (msg.type === 'strategyEvaluation' && msg.evaluation) {
      this.strategies.set(msg.evaluation.strategies);
      this.evaluatedAtLap.set(msg.evaluatedAtLap ?? 0);
      this.stale.set(msg.stale ?? false);
    }
  }
}
