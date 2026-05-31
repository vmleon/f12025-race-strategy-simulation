import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface ActiveSessionDto {
  sessionUid: string;
  trackName: string;
  sessionType: string;
  live: boolean;
}

@Injectable({ providedIn: 'root' })
export class SessionService {
  constructor(private http: HttpClient) {}

  getActiveSessions() {
    return this.http.get<ActiveSessionDto[]>('/api/sessions/active');
  }
}
