import { Component, Input } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RankedStrategy } from '../../race.service';

const COMPOUND_NAMES: Record<number, string> = {
  16: 'S',
  17: 'M',
  18: 'H',
  7: 'I',
  8: 'W',
};

interface BadgeStyle {
  bg: string;
  fg: string;
  border: boolean;
}

// Official F1 compound colours. Medium/Hard use dark text; Hard also gets a
// border so the near-white circle reads on the dark panel.
const COMPOUND_STYLE: Record<string, BadgeStyle> = {
  S: { bg: '#DA291C', fg: '#ffffff', border: false },
  M: { bg: '#FFD12E', fg: '#1a1a1a', border: false },
  H: { bg: '#F0F0EC', fg: '#1a1a1a', border: true },
  I: { bg: '#43B02A', fg: '#ffffff', border: false },
  W: { bg: '#0067AD', fg: '#ffffff', border: false },
};

const UNKNOWN_BADGE: BadgeStyle = { bg: '#444', fg: '#fff', border: false };

@Component({
  selector: 'app-strategy-widget',
  imports: [DecimalPipe, PercentPipe],
  template: `
    <div class="panel">
      <div class="panel-header">
        <h3>Best Strategies</h3>
      </div>

      @if (stale) {
        <div class="stale-bar">Updating...</div>
      }

      @if (insufficientCalibration) {
        <p class="insufficient">
          Insufficient calibration — pace is using the circuit default. Run more clean laps for
          reliable strategy estimates.
        </p>
      } @else if (strategies.length > 0) {
        @if (evaluatedAtLap > 0) {
          <div class="meta">Lap {{ evaluatedAtLap }}</div>
        }
        @for (s of strategies.slice(0, 3); track s.rank) {
          <div class="strategy-row" [class.top]="s.rank === 1">
            <span class="rank">{{ s.rank }}</span>
            <span class="stints" [title]="s.candidate.label">
              @if (startingCompound) {
                <span
                  class="badge"
                  [class.bordered]="badgeStyle(startingCompound).border"
                  [style.background]="badgeStyle(startingCompound).bg"
                  [style.color]="badgeStyle(startingCompound).fg"
                  >{{ startingCompound }}</span
                >
              }
              @for (st of s.candidate.stops; track st.onLap) {
                <span class="arrow">→</span>
                <span
                  class="badge"
                  [class.bordered]="badgeStyle(letterFor(st.newCompound)).border"
                  [style.background]="badgeStyle(letterFor(st.newCompound)).bg"
                  [style.color]="badgeStyle(letterFor(st.newCompound)).fg"
                  >{{ letterFor(st.newCompound) }}</span
                >
                <span class="pit-lap">L{{ st.onLap }}</span>
              }
            </span>
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
    .stints {
      flex: 1;
      display: flex;
      align-items: center;
      gap: 0.25rem;
      overflow: hidden;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: none;
      width: 1.15rem;
      height: 1.15rem;
      border-radius: 50%;
      font-size: 0.7rem;
      font-weight: 700;
      line-height: 1;
    }
    /* Hard (near-white) needs an outline to read on the dark panel. */
    .badge.bordered {
      box-shadow: inset 0 0 0 1px #b9b9b3;
    }
    .arrow {
      color: #666;
      font-size: 0.7rem;
    }
    .pit-lap {
      color: #999;
      font-size: 0.7rem;
      font-family: monospace;
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
    .insufficient {
      color: #ffa726;
      font-size: 0.8rem;
      line-height: 1.3;
      margin: 0;
    }
  `,
})
export class StrategyWidgetComponent {
  @Input() strategies: RankedStrategy[] = [];
  @Input() evaluatedAtLap = 0;
  @Input() stale = false;
  @Input() insufficientCalibration = false;
  /** The car's current tyre letter (S/M/H/I/W) — the strategy's starting compound. */
  @Input() startingCompound = '';

  letterFor(compound: number): string {
    return COMPOUND_NAMES[compound] ?? '?';
  }

  badgeStyle(letter: string): BadgeStyle {
    return COMPOUND_STYLE[letter] ?? UNKNOWN_BADGE;
  }
}
