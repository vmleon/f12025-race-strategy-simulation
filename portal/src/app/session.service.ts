import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface SessionDto {
  sessionUid: string;
  trackId: number;
  sessionType: string;
  totalLaps: number;
  aiDifficulty: number;
  createdAt: string;
}

export interface ParticipantDto {
  carIndex: number;
  driverName: string;
  teamId: number;
  aiControlled: boolean;
}

export interface SessionDetailDto extends SessionDto {
  participants: ParticipantDto[];
}

export interface SectorSnapshotDto {
  carIndex: number;
  lapNumber: number;
  sectorNumber: number;
  sectorTimeMs: number;
  carPosition: number;
  tyreCompoundActual: string;
  tyreAgeLaps: number;
  weather: number;
}

@Injectable({ providedIn: 'root' })
export class SessionService {
  constructor(private http: HttpClient) {}

  getSessions(trackId?: number, limit = 20) {
    const params: Record<string, string> = { limit: String(limit) };
    if (trackId != null) params['trackId'] = String(trackId);
    return this.http.get<SessionDto[]>('/api/sessions', { params });
  }

  getSession(sessionUid: string) {
    return this.http.get<SessionDetailDto>(`/api/sessions/${sessionUid}`);
  }

  getSectors(sessionUid: string, carIndex?: number, lap?: number) {
    const params: Record<string, string> = {};
    if (carIndex != null) params['carIndex'] = String(carIndex);
    if (lap != null) params['lap'] = String(lap);
    return this.http.get<SectorSnapshotDto[]>(`/api/sessions/${sessionUid}/sectors`, { params });
  }
}
