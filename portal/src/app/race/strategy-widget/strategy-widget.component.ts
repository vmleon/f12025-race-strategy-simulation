import { Component, Input } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { SimulationCarResult } from '../../race.service';

@Component({
  selector: 'app-strategy-widget',
  imports: [DecimalPipe, PercentPipe, RouterLink],
  template: `
    <div class="panel">
      <div class="panel-header">
        <h3>Strategy</h3>
        <a routerLink="/strategy" class="details-link">View details</a>
      </div>

      @if (simulating) {
        <div class="sim-status">
          <span class="spinner"></span>
          <span>Simulating...</span>
        </div>
      }

      @if (playerResult) {
        <div class="meta">
          <span>{{ iterations | number }} iter</span>
          <span [class.converged]="converged" [class.not-converged]="!converged">
            {{ converged ? 'Converged' : 'Not converged' }}
          </span>
          @if (lastUpdated) {
            <span class="timestamp">{{ lastUpdated }}</span>
          }
        </div>

        <div class="strategy-card top">
          <div class="card-header">
            <span class="rank">1st</span>
            <span class="driver">{{ playerResult.driverName }}</span>
          </div>
          <div class="card-stats">
            <div class="stat">
              <span class="stat-label">Pred. Pos</span>
              <span class="stat-value">
                {{ playerResult.meanPosition | number : '1.1-1' }}
                <span class="ci">
                  ({{ playerResult.ci95Low | number : '1.1-1' }}–{{
                    playerResult.ci95High | number : '1.1-1'
                  }})
                </span>
              </span>
            </div>
            <div class="stat">
              <span class="stat-label">P(Podium)</span>
              <span class="stat-value">{{ playerResult.top3Probability | percent : '1.0-0' }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">P(DNF)</span>
              <span
                class="stat-value"
                [class.risk-high]="playerResult.dnfProbability > 0.1"
                [class.risk-medium]="
                  playerResult.dnfProbability > 0.03 && playerResult.dnfProbability <= 0.1
                "
              >
                {{ playerResult.dnfProbability | percent : '1.0-0' }}
              </span>
            </div>
          </div>
          @if (playerTyre) {
            <div class="stint-plan">
              <span class="stat-label">Current</span>
              <span class="tyre" [attr.data-tyre]="playerTyre">{{ playerTyre }}</span>
            </div>
          }
        </div>
      } @else if (!simulating) {
        <p class="empty">No simulation data</p>
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

    .sim-status {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-size: 0.8rem;
      color: #bbb;
      margin-bottom: 0.5rem;
    }
    .spinner {
      width: 12px;
      height: 12px;
      border: 2px solid #555;
      border-top-color: #42a5f5;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin {
      to {
        transform: rotate(360deg);
      }
    }

    .meta {
      display: flex;
      gap: 0.5rem;
      font-size: 0.7rem;
      color: #777;
      margin-bottom: 0.5rem;
    }
    .converged {
      color: #66bb6a;
    }
    .not-converged {
      color: #ef5350;
    }
    .timestamp {
      margin-left: auto;
    }

    .strategy-card {
      border: 1px solid #333;
      border-radius: 6px;
      padding: 0.5rem 0.6rem;
    }
    .strategy-card.top {
      border-color: #43a047;
      background: #1a2a1a;
    }
    .card-header {
      display: flex;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.4rem;
    }
    .rank {
      font-size: 0.75rem;
      font-weight: bold;
      color: #43a047;
    }
    .driver {
      font-size: 0.85rem;
      font-weight: bold;
      color: #e0e0e0;
    }

    .card-stats {
      display: flex;
      gap: 0.75rem;
      flex-wrap: wrap;
    }
    .stat {
      display: flex;
      flex-direction: column;
    }
    .stat-label {
      font-size: 0.65rem;
      color: #888;
      text-transform: uppercase;
    }
    .stat-value {
      font-size: 0.85rem;
      color: #e0e0e0;
    }
    .ci {
      font-size: 0.7rem;
      color: #999;
    }

    .risk-high {
      color: #ef5350;
    }
    .risk-medium {
      color: #ffa726;
    }

    .stint-plan {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      margin-top: 0.4rem;
      padding-top: 0.4rem;
      border-top: 1px solid #333;
    }
    .tyre {
      padding: 0.1rem 0.35rem;
      border-radius: 3px;
      font-size: 0.75rem;
      font-weight: bold;
    }
    .tyre[data-tyre='S'] {
      background: #e10600;
      color: #fff;
    }
    .tyre[data-tyre='M'] {
      background: #ffd600;
      color: #000;
    }
    .tyre[data-tyre='H'] {
      background: #eee;
      color: #000;
    }
    .tyre[data-tyre='I'] {
      background: #43a047;
      color: #fff;
    }
    .tyre[data-tyre='W'] {
      background: #1565c0;
      color: #fff;
    }

    .empty {
      color: #666;
      font-size: 0.8rem;
      margin: 0;
    }
  `,
})
export class StrategyWidgetComponent {
  @Input() playerResult: SimulationCarResult | null = null;
  @Input() iterations = 0;
  @Input() converged = false;
  @Input() simulating = false;
  @Input() lastUpdated: string | null = null;
  @Input() playerTyre: string | null = null;
}
