import { Component, OnInit, signal } from '@angular/core';
import {
  DriverService,
  DriverDto,
  DriverDetailDto,
  DriverSessionDto,
} from '../driver.service';
import {
  SessionService,
  SessionDto,
  ParticipantDto,
} from '../session.service';
import { trackName } from '../track-names';

@Component({
  selector: 'app-drivers',
  template: `
    @if (!selectedDriver()) {
      <!-- ── List view ──────────────────────────────────────────── -->
      <div class="header">
        <h2>Drivers</h2>
        <button (click)="showForm.set(!showForm())">
          {{ showForm() ? 'Cancel' : 'Add driver' }}
        </button>
      </div>

      @if (showForm()) {
        <div class="form">
          <label>Name <input [value]="formName()" (input)="formName.set(val($event))" /></label>
          <label>Email <input [value]="formEmail()" (input)="formEmail.set(val($event))" type="email" /></label>
          <button [disabled]="!formName().trim()" (click)="save()">
            {{ editingId() ? 'Update' : 'Create' }}
          </button>
          @if (formError()) {
            <span class="error">{{ formError() }}</span>
          }
        </div>
      }

      @if (drivers().length > 0) {
        <table class="driver-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Email</th>
              <th>Sessions</th>
              <th>Created</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (d of drivers(); track d.driverId) {
              <tr>
                <td><a class="link" (click)="selectDriver(d.driverId)">{{ d.name }}</a></td>
                <td>{{ d.email ?? '—' }}</td>
                <td>{{ d.sessionCount }}</td>
                <td>{{ d.createdAt }}</td>
                <td class="actions">
                  <button class="small-btn" (click)="startEdit(d)">Edit</button>
                  <button class="small-btn danger" (click)="deleteDriver(d)">Delete</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      } @else if (!loading()) {
        <p class="empty">No drivers yet.</p>
      }
    } @else {
      <!-- ── Detail view ────────────────────────────────────────── -->
      <button class="back-btn" (click)="back()">&larr; Back to list</button>

      <div class="detail-header">
        <h3>{{ selectedDriver()!.name }}</h3>
        <span class="meta">{{ selectedDriver()!.email ?? 'No email' }} | Created {{ selectedDriver()!.createdAt }}</span>
      </div>

      <h4>Associated Sessions</h4>
      @if (selectedDriver()!.sessions.length > 0) {
        <table class="session-table">
          <thead>
            <tr>
              <th>Track</th>
              <th>Type</th>
              <th>Car #</th>
              <th>Date</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (s of selectedDriver()!.sessions; track s.sessionUid) {
              <tr>
                <td>{{ trackLabel(s.trackId) }}</td>
                <td>{{ s.sessionType }}</td>
                <td>{{ s.carIndex }}</td>
                <td>{{ s.createdAt }}</td>
                <td>
                  <button class="small-btn danger" (click)="removeSession(s.sessionUid)">Remove</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <p class="empty">No sessions associated.</p>
      }

      <h4>Associate Session</h4>
      <div class="assoc-form">
        <label>
          Session
          <select (change)="onSessionSelect($event)">
            <option value="">Select session…</option>
            @for (s of availableSessions(); track s.sessionUid) {
              <option [value]="s.sessionUid">{{ trackLabel(s.trackId) }} — {{ s.sessionType }} ({{ s.createdAt }})</option>
            }
          </select>
        </label>
        @if (participants().length > 0) {
          <label>
            Car
            <select (change)="assocCarIndex.set(+val($event))">
              <option value="">Select car…</option>
              @for (p of participants(); track p.carIndex) {
                <option [value]="p.carIndex">{{ p.carIndex }} — {{ p.driverName }} {{ p.aiControlled ? '(AI)' : '' }}</option>
              }
            </select>
          </label>
        }
        <button [disabled]="!assocSessionUid() || assocCarIndex() == null" (click)="associateSession()">
          Associate
        </button>
        @if (assocError()) {
          <span class="error">{{ assocError() }}</span>
        }
      </div>
    }
  `,
  styles: `
    .header { display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem; }
    .header h2 { margin: 0; }

    .form {
      display: flex; align-items: center; gap: 0.75rem;
      margin-bottom: 1rem; flex-wrap: wrap;
    }
    .form label { display: flex; align-items: center; gap: 0.35rem; }
    .form input, .assoc-form select, .assoc-form input {
      background: #222; border: 1px solid #555; color: #eee;
      padding: 0.3rem 0.5rem; border-radius: 3px;
    }

    .header button, .back-btn, .form button, .assoc-form button {
      padding: 0.35rem 0.7rem; background: #333; color: #eee;
      border: 1px solid #555; border-radius: 4px; cursor: pointer; font-size: 0.85rem;
    }
    .back-btn { margin-bottom: 1rem; }

    .detail-header { margin-bottom: 1rem; }
    .detail-header h3 { margin: 0 0 0.25rem; }
    .meta { color: #999; font-size: 0.85rem; }
    h4 { margin: 1.25rem 0 0.5rem; color: #ccc; }

    .driver-table, .session-table {
      width: 100%; border-collapse: collapse; font-size: 0.9rem;
    }
    .driver-table th, .session-table th {
      text-align: left; padding: 0.4rem 0.6rem; border-bottom: 2px solid #444;
      color: #999; font-size: 0.8rem; text-transform: uppercase;
    }
    .driver-table td, .session-table td {
      padding: 0.35rem 0.6rem; border-bottom: 1px solid #2a2a2a;
    }

    .actions { display: flex; gap: 0.4rem; }
    .small-btn {
      padding: 0.2rem 0.5rem; background: #333; color: #eee;
      border: 1px solid #555; border-radius: 3px; cursor: pointer; font-size: 0.8rem;
    }
    .small-btn.danger { border-color: #a33; }
    .small-btn.danger:hover { background: #a33; }

    .link { color: #6cf; cursor: pointer; text-decoration: underline; }

    .assoc-form {
      display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap;
    }
    .assoc-form label { display: flex; align-items: center; gap: 0.35rem; }
    .assoc-form select {
      background: #222; border: 1px solid #555; color: #eee;
      padding: 0.3rem 0.5rem; border-radius: 3px;
    }

    .error { color: #f66; font-size: 0.85rem; }
    .empty { color: #888; }
  `,
})
export class DriversComponent implements OnInit {
  drivers = signal<DriverDto[]>([]);
  selectedDriver = signal<DriverDetailDto | null>(null);
  loading = signal(false);

  // Create / Edit form
  showForm = signal(false);
  editingId = signal<number | null>(null);
  formName = signal('');
  formEmail = signal('');
  formError = signal('');

  // Session association
  availableSessions = signal<SessionDto[]>([]);
  participants = signal<ParticipantDto[]>([]);
  assocSessionUid = signal('');
  assocCarIndex = signal<number | null>(null);
  assocError = signal('');

  constructor(
    private driverService: DriverService,
    private sessionService: SessionService,
  ) {}

  ngOnInit() {
    this.loadDrivers();
  }

  val(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  loadDrivers() {
    this.loading.set(true);
    this.driverService.list().subscribe({
      next: (data) => {
        this.drivers.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  // ── CRUD ─────────────────────────────────────────────────────────────

  save() {
    const name = this.formName().trim();
    if (!name) return;
    const email = this.formEmail().trim() || null;
    this.formError.set('');

    const id = this.editingId();
    const req = id != null
      ? this.driverService.update(id, name, email)
      : this.driverService.create(name, email);

    req.subscribe({
      next: () => {
        this.resetForm();
        this.loadDrivers();
      },
      error: (err) => this.formError.set(err.error?.error ?? 'Failed'),
    });
  }

  startEdit(d: DriverDto) {
    this.editingId.set(d.driverId);
    this.formName.set(d.name);
    this.formEmail.set(d.email ?? '');
    this.showForm.set(true);
  }

  deleteDriver(d: DriverDto) {
    this.driverService.delete(d.driverId).subscribe({
      next: () => this.loadDrivers(),
    });
  }

  private resetForm() {
    this.showForm.set(false);
    this.editingId.set(null);
    this.formName.set('');
    this.formEmail.set('');
    this.formError.set('');
  }

  // ── Detail view ────────────────────────────────────────────────────

  selectDriver(id: number) {
    this.driverService.get(id).subscribe({
      next: (detail) => {
        this.selectedDriver.set(detail);
        this.loadAvailableSessions();
      },
    });
  }

  back() {
    this.selectedDriver.set(null);
    this.participants.set([]);
    this.assocSessionUid.set('');
    this.assocCarIndex.set(null);
    this.assocError.set('');
  }

  // ── Session association ────────────────────────────────────────────

  private loadAvailableSessions() {
    this.sessionService.getSessions(undefined, 100).subscribe({
      next: (data) => this.availableSessions.set(data),
    });
  }

  onSessionSelect(event: Event) {
    const uid = (event.target as HTMLSelectElement).value;
    this.assocSessionUid.set(uid);
    this.assocCarIndex.set(null);
    this.participants.set([]);
    if (!uid) return;
    this.sessionService.getSession(uid).subscribe({
      next: (detail) => this.participants.set(detail.participants),
    });
  }

  associateSession() {
    const driver = this.selectedDriver();
    const uid = this.assocSessionUid();
    const carIdx = this.assocCarIndex();
    if (!driver || !uid || carIdx == null) return;
    this.assocError.set('');

    this.driverService.associateSession(driver.driverId, uid, carIdx).subscribe({
      next: () => {
        this.assocSessionUid.set('');
        this.assocCarIndex.set(null);
        this.participants.set([]);
        this.selectDriver(driver.driverId);
      },
      error: (err) => this.assocError.set(err.error?.error ?? 'Failed'),
    });
  }

  removeSession(sessionUid: string) {
    const driver = this.selectedDriver();
    if (!driver) return;
    this.driverService.removeSession(driver.driverId, sessionUid).subscribe({
      next: () => this.selectDriver(driver.driverId),
    });
  }

  trackLabel(id: number): string {
    return trackName(id);
  }
}
