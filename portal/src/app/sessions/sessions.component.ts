import { Component, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import {
  SessionService,
  SessionDto,
  SessionDetailDto,
  SectorSnapshotDto,
} from '../session.service';
import { trackName } from '../track-names';
import { sessionTypeName } from '../session-types';
import { teamName } from '../team-names';
import { tyreCompoundName } from '../tyre-compounds';
import { formatTime } from '../format-time';

@Component({
  selector: 'app-sessions',
  imports: [DatePipe],
  template: `
    <div class="header">
      <h2>Sessions</h2>
      <label>
        Filter track ID:
        <input
          type="number"
          [value]="filterTrackId()"
          (input)="onFilterChange($event)"
          min="0"
          max="40"
          placeholder="all"
          style="width: 4rem"
        />
      </label>
      <button (click)="loadSessions()">Refresh</button>
    </div>

    @if (!selectedSession()) {
      @if (sessions().length > 0) {
        <table class="session-table">
          <thead>
            <tr>
              <th>Track</th>
              <th>Type</th>
              <th>Laps</th>
              <th>AI Difficulty</th>
              <th>Date</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (s of sessions(); track s.sessionUid) {
              <tr>
                <td>{{ trackLabel(s.trackId) }}</td>
                <td>{{ sessionTypeLabel(s.sessionType) }}</td>
                <td>{{ s.totalLaps }}</td>
                <td>{{ s.aiDifficulty }}</td>
                <td>{{ s.createdAt | date: 'd MMM yyyy, HH:mm' }}</td>
                <td><button class="detail-btn" (click)="selectSession(s.sessionUid)">Details</button></td>
              </tr>
            }
          </tbody>
        </table>
      } @else if (!loading()) {
        <p class="empty">No sessions found.</p>
      }
    } @else {
      <button class="back-btn" (click)="back()">&larr; Back to list</button>

      <div class="detail-header">
        <h3>{{ trackLabel(selectedSession()!.trackId) }} — {{ sessionTypeLabel(selectedSession()!.sessionType) }}</h3>
        <span class="meta">{{ selectedSession()!.totalLaps }} laps | AI {{ selectedSession()!.aiDifficulty }} | {{ selectedSession()!.createdAt | date: 'd MMM yyyy, HH:mm' }}</span>
      </div>

      @if (selectedSession()!.participants.length > 0) {
        <h4>Participants</h4>
        <table class="part-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Driver</th>
              <th>Team</th>
              <th>AI</th>
            </tr>
          </thead>
          <tbody>
            @for (p of selectedSession()!.participants; track p.carIndex) {
              <tr [class.ai]="p.aiControlled">
                <td>{{ p.carIndex }}</td>
                <td>{{ p.driverName }}</td>
                <td>{{ teamLabel(p.teamId) }}</td>
                <td>{{ p.aiControlled ? 'Yes' : 'No' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }

      @if (sectors().length > 0) {
        <h4>Sector Snapshots</h4>
        <table class="sector-table">
          <thead>
            <tr>
              <th>Car</th>
              <th>Lap</th>
              <th>Sector</th>
              <th>Time</th>
              <th>Pos</th>
              <th>Tyre</th>
              <th>Age</th>
            </tr>
          </thead>
          <tbody>
            @for (s of sectors(); track s.carIndex + '-' + s.lapNumber + '-' + s.sectorNumber) {
              <tr>
                <td>{{ s.carIndex }}</td>
                <td>{{ s.lapNumber }}</td>
                <td>{{ s.sectorNumber }}</td>
                <td class="mono">{{ formatTimeMs(s.sectorTimeMs) }}</td>
                <td>{{ s.carPosition }}</td>
                <td>{{ tyreLabel(s.tyreCompoundVisual) }}</td>
                <td>{{ s.tyreAgeLaps }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
  styles: `
    .header { display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem; }
    .header h2 { margin: 0; }
    .header input {
      background: #222;
      border: 1px solid #555;
      color: #eee;
      padding: 0.25rem 0.4rem;
      border-radius: 3px;
    }
    .header button, .back-btn, .detail-btn {
      padding: 0.35rem 0.7rem;
      background: #333;
      color: #eee;
      border: 1px solid #555;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.85rem;
    }
    .back-btn { margin-bottom: 1rem; }

    .detail-header { margin-bottom: 1rem; }
    .detail-header h3 { margin: 0 0 0.25rem; }
    .meta { color: #999; font-size: 0.85rem; }

    h4 { margin: 1.25rem 0 0.5rem; color: #ccc; }

    .session-table, .part-table, .sector-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.9rem;
    }
    .session-table th, .part-table th, .sector-table th {
      text-align: left;
      padding: 0.4rem 0.6rem;
      border-bottom: 2px solid #444;
      color: #999;
      font-size: 0.8rem;
      text-transform: uppercase;
    }
    .session-table td, .part-table td, .sector-table td {
      padding: 0.35rem 0.6rem;
      border-bottom: 1px solid #2a2a2a;
    }
    .part-table tr.ai { opacity: 0.7; }
    .mono { font-family: monospace; }
    .empty { color: #888; }
  `,
})
export class SessionsComponent implements OnInit {
  sessions = signal<SessionDto[]>([]);
  selectedSession = signal<SessionDetailDto | null>(null);
  sectors = signal<SectorSnapshotDto[]>([]);
  filterTrackId = signal<number | null>(null);
  loading = signal(false);

  constructor(private sessionService: SessionService) {}

  ngOnInit() {
    this.loadSessions();
  }

  onFilterChange(event: Event) {
    const val = (event.target as HTMLInputElement).valueAsNumber;
    this.filterTrackId.set(isNaN(val) ? null : val);
  }

  loadSessions() {
    this.loading.set(true);
    const tid = this.filterTrackId();
    this.sessionService.getSessions(tid ?? undefined).subscribe({
      next: (data) => {
        this.sessions.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  selectSession(uid: string) {
    this.sessionService.getSession(uid).subscribe({
      next: (detail) => {
        this.selectedSession.set(detail);
        this.sessionService.getSectors(uid).subscribe({
          next: (data) => this.sectors.set(data),
        });
      },
    });
  }

  back() {
    this.selectedSession.set(null);
    this.sectors.set([]);
  }

  trackLabel(id: number): string {
    return trackName(id);
  }

  sessionTypeLabel(code: string): string {
    return sessionTypeName(Number(code));
  }

  teamLabel(id: number): string {
    return teamName(id);
  }

  tyreLabel(code: number): string {
    return tyreCompoundName(code);
  }

  formatTimeMs(ms: number): string {
    return formatTime(ms);
  }
}
