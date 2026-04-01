import { Component, Input } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { RankedStrategy } from '../../race.service';

const COMPOUND_NAMES: Record<number, string> = {
  16: 'S',
  17: 'M',
  18: 'H',
  7: 'I',
  8: 'W',
};

@Component({
  selector: 'app-strategy-widget',
  imports: [DecimalPipe, PercentPipe, RouterLink],
  template: `
    <div class="panel">
      <div class="panel-header">
        <h3>Best Strategies</h3>
        <a routerLink="/strategy" class="details-link">View all</a>
      </div>

      @if (stale) {
        <div class="stale-bar">Updating...</div>
      }

      @if (strategies.length > 0) {
        @if (evaluatedAtLap > 0) {
          <div class="meta">Lap {{ evaluatedAtLap }}</div>
        }
        @for (s of strategies.slice(0, 3); track s.rank) {
          <div class="strategy-row" [class.top]="s.rank === 1">
            <span class="rank">{{ s.rank }}</span>
            <span class="label">{{ s.candidate.label }}</span>
            <span class="pos">P{{ s.meanPosition | number : '1.1-1' }}</span>
            <span class="podium">{{ s.top3Probability | percent : '1.0-0' }}</span>
          </div>
        }
      } @else {
        <p class="empty">No strategy data</p>
      }
    </div>
  `,
  styles: `
    .panel {
      background: #1a1a1a;
      border-radius: 8px;
      padding: 0.75rem 1rem;
    }
    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    h3 {
      margin: 0;
      font-size: 0.9rem;
      color: #999;
      text-transform: uppercase;
    }
    .details-link {
      font-size: 0.75rem;
      color: #42a5f5;
      text-decoration: none;
    }
    .details-link:hover {
      text-decoration: underline;
    }

    .stale-bar {
      font-size: 0.7rem;
      color: #ffa726;
      font-style: italic;
      margin-bottom: 0.4rem;
    }

    .meta {
      font-size: 0.7rem;
      color: #777;
      margin-bottom: 0.4rem;
    }

    .strategy-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.3rem 0.4rem;
      border-bottom: 1px solid #2a2a2a;
      font-size: 0.8rem;
    }
    .strategy-row.top {
      background: #1a2a1a;
      border-radius: 4px;
      border-bottom: none;
    }
    .rank {
      font-weight: bold;
      color: #999;
      min-width: 1.2rem;
    }
    .label {
      flex: 1;
      color: #e0e0e0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .pos {
      color: #e0e0e0;
      font-family: monospace;
    }
    .podium {
      color: #66bb6a;
      font-size: 0.75rem;
    }

    .empty {
      color: #666;
      font-size: 0.8rem;
      margin: 0;
    }
  `,
})
export class StrategyWidgetComponent {
  @Input() strategies: RankedStrategy[] = [];
  @Input() evaluatedAtLap = 0;
  @Input() stale = false;
}
