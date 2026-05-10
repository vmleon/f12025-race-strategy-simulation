import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, defer, timer } from 'rxjs';
import { retry, share } from 'rxjs/operators';
import { webSocket } from 'rxjs/webSocket';

@Injectable({ providedIn: 'root' })
export class HealthService {
  readonly heartbeat$: Observable<{ ts: number }> = defer(() =>
    webSocket<{ ts: number }>(
      `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/heartbeat`
    )
  ).pipe(
    retry({ delay: (_err, attempt) => timer(Math.min(1000 * 2 ** attempt, 30000)) }),
    share()
  );

  constructor(private http: HttpClient) {}

  getHealth() {
    return this.http.get<{ status: string; timestamp: number }>('/api/health');
  }
}
