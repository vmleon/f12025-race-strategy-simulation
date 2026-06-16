import { Component, Input, OnChanges, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, interval } from 'rxjs';
import { ReadinessService, FitConfidence } from './readiness.service';

@Component({
  selector: 'app-fit-confidence',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="fit-card">
      <h3>Fit confidence per knob</h3>
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
  `,
  styles: [
    `
      .fit-card { margin-top: 1rem; background: var(--surface, #1b1b1b); border-radius: 8px; padding: 1rem; }
      .fit-card h3 { margin: 0 0 0.75rem; font-size: 0.95rem; }
      table.fit { width: 100%; border-collapse: collapse; font-size: 0.74rem; }
      .fit th { text-align: left; color: var(--gray-500, #999); font-weight: 500; padding: 0.2rem 0.4rem; }
      .fit td { border: none; border-bottom: 1px solid #2a2a2a; text-align: left; padding: 0.2rem 0.4rem; }
      .flag { font-size: 0.68rem; background: #d9534f; color: #fff; border-radius: 3px; padding: 0 0.3rem; }
      .empty { color: var(--gray-500, #999); font-size: 0.82rem; }
    `,
  ],
})
export class FitConfidenceComponent implements OnChanges, OnInit, OnDestroy {
  @Input() trackId?: number;

  fit = signal<FitConfidence[]>([]);

  private pollSub?: Subscription;

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

  private refresh(): void {
    if (this.trackId == null) return;
    this.svc.fitConfidence(this.trackId).subscribe((f) => this.fit.set(f));
  }
}
