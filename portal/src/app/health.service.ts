import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { webSocket } from 'rxjs/webSocket';

@Injectable({ providedIn: 'root' })
export class HealthService {
  readonly heartbeat$ = webSocket<{ ts: number }>(
    `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/heartbeat`
  );

  constructor(private http: HttpClient) {}

  getHealth() {
    return this.http.get<{ status: string; timestamp: number }>('/api/health');
  }
}
