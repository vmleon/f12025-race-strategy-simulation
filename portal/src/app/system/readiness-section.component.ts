import { Component, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, interval } from 'rxjs';
import {
  ReadinessService,
  ReadinessResponse,
  CompoundReadiness,
  TrackOption,
} from './readiness.service';

@Component({
  selector: 'app-readiness-section',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="readiness card">
      <div class="readiness__head">
        <h3>Calibration Readiness</h3>
        <div class="controls">
          <select [value]="selectedTrackId ?? ''" (change)="onTrackChange($event)">
            <option *ngFor="let t of tracks" [value]="t.trackId">{{ t.trackName }}</option>
          </select>
        </div>
      </div>

      <div class="summary" *ngIf="data">
        <div class="tile">
          <span>{{ data.overallConfidence * 100 | number: '1.0-0' }}%</span>sim confidence
        </div>
        <div class="tile"><span>{{ data.fuelEffectFitted ? '✓' : '✗' }}</span>fuel effect</div>
        <div class="tile last"><span>last calibrated</span>{{ data.calibrationLastRanAt ?? 'never' }}</div>
      </div>

      <table class="rows" *ngIf="data">
        <tbody>
          <ng-container *ngFor="let c of data.compounds">
            <tr class="row" (click)="toggle(c.compound)">
              <td class="exp">{{ expanded.has(c.compound) ? '▾' : '▸' }}</td>
              <td class="name">{{ c.name }}</td>
              <td class="ready" [class.on]="c.wearFitted">
                {{ c.wearFitted ? '✓ cliff' : '— cliff' }}
              </td>
              <td class="count secondary">good {{ c.good }} / {{ c.total }}</td>
              <td class="conf secondary">
                <span class="bar"><i [style.width.%]="c.confidence * 100"></i></span>
                {{ c.confidence * 100 | number: '1.0-0' }}%
              </td>
              <td class="fit">
                <span [class.on]="c.baselineFitted">pace</span>
                <span [class.on]="c.degFitted">deg</span>
              </td>
              <td class="reasons">{{ reasonText(c) }}</td>
            </tr>
            <tr class="sectors" *ngIf="expanded.has(c.compound)">
              <td></td>
              <td colspan="6">
                <span *ngFor="let s of c.sectors" class="sector">
                  S{{ s.sector + 1 }} {{ s.good }}/{{ s.total }}
                  ({{ s.confidence * 100 | number: '1.0-0' }}%)
                </span>
              </td>
            </tr>
          </ng-container>
        </tbody>
      </table>

      <p class="empty" *ngIf="data && data.compounds.length === 0">No session data for this track yet.</p>
    </div>
  `,
  styles: [
    `
      .readiness { margin-top: 1rem; }
      .readiness__head { display: flex; justify-content: space-between; align-items: center; }
      .readiness__head h3 { margin: 0; font-size: 0.95rem; }
      .controls { display: flex; gap: 0.5rem; }
      .summary { display: flex; gap: 1.5rem; margin: 0.75rem 0; }
      .tile { display: flex; flex-direction: column; font-size: 0.7rem; color: var(--gray-500, #999); }
      .tile span { font-size: 1.3rem; font-weight: 600; color: var(--gray-100, #eee); }
      .tile.last span { font-size: 0.7rem; font-weight: 400; }
      .rows { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
      .row { cursor: pointer; border-top: 1px solid #2a2a2a; }
      .row td { padding: 0.4rem 0.5rem; }
      .exp { width: 1rem; color: var(--gray-500, #999); }
      .name { font-weight: 600; }
      .conf { white-space: nowrap; }
      .ready { font-weight: 600; font-size: 0.8rem; white-space: nowrap; color: var(--gray-500, #999); }
      .ready.on { color: #4caf50; }
      .secondary { opacity: 0.55; }
      .bar { display: inline-block; width: 80px; height: 8px; background: #2a2a2a; border-radius: 4px; vertical-align: middle; margin-right: 0.4rem; overflow: hidden; }
      .bar i { display: block; height: 100%; background: #4caf50; }
      .fit span { font-size: 0.7rem; color: #555; margin-right: 0.4rem; }
      .fit span.on { color: #4caf50; }
      .reasons { color: var(--gray-500, #999); font-size: 0.75rem; }
      .sectors td { color: var(--gray-500, #999); font-size: 0.78rem; padding-bottom: 0.5rem; }
      .sector { margin-right: 1rem; }
      .empty { color: var(--gray-500, #999); font-size: 0.85rem; }
    `,
  ],
})
export class ReadinessSectionComponent implements OnInit, OnDestroy {
  tracks: TrackOption[] = [];
  selectedTrackId?: number;
  data?: ReadinessResponse;
  expanded = new Set<number>();
  private pollSub?: Subscription;

  /** Emits whenever the selected track changes, so the degradation charts below
   * can follow the same track without a second picker. */
  @Output() trackChange = new EventEmitter<number | undefined>();

  constructor(private svc: ReadinessService) {}

  ngOnInit(): void {
    this.svc.tracks().subscribe((ts) => {
      this.tracks = ts;
      this.selectedTrackId = ts.length ? ts[0].trackId : undefined;
      this.trackChange.emit(this.selectedTrackId);
      this.refresh();
    });
    // Auto-refresh readiness for the selected track while a session runs.
    this.pollSub = interval(15_000).subscribe(() => this.refresh());
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  onTrackChange(ev: Event): void {
    this.selectedTrackId = Number((ev.target as HTMLSelectElement).value);
    this.trackChange.emit(this.selectedTrackId);
    this.refresh();
  }

  refresh(): void {
    this.svc.readiness(this.selectedTrackId).subscribe((d) => (this.data = d));
  }

  toggle(compound: number): void {
    if (this.expanded.has(compound)) this.expanded.delete(compound);
    else this.expanded.add(compound);
  }

  reasonText(c: CompoundReadiness): string {
    const r = c.reasons;
    const parts: string[] = [];
    if (r.outlier) parts.push(`outlier ${r.outlier}`);
    if (r.invalid) parts.push(`invalid ${r.invalid}`);
    if (r.cornerCut) parts.push(`corner-cut ${r.cornerCut}`);
    if (r.damage) parts.push(`damage ${r.damage}`);
    if (r.pit) parts.push(`pit ${r.pit}`);
    if (r.safetyCar) parts.push(`SC ${r.safetyCar}`);
    if (r.standingStart) parts.push(`start ${r.standingStart}`);
    return parts.length ? parts.join(' · ') : '—';
  }
}
