import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface DriverDto {
  driverId: number;
  name: string;
  email: string | null;
  createdAt: string;
  sessionCount: number;
}

export interface DriverSessionDto {
  sessionUid: string;
  carIndex: number;
  trackId: number;
  sessionType: string;
  createdAt: string;
}

export interface DriverDetailDto extends DriverDto {
  sessions: DriverSessionDto[];
}

@Injectable({ providedIn: 'root' })
export class DriverService {
  constructor(private http: HttpClient) {}

  list() {
    return this.http.get<DriverDto[]>('/api/drivers');
  }

  get(id: number) {
    return this.http.get<DriverDetailDto>(`/api/drivers/${id}`);
  }

  create(name: string, email: string | null) {
    return this.http.post<{ driverId: number; name: string }>('/api/drivers', { name, email });
  }

  update(id: number, name: string, email: string | null) {
    return this.http.put<{ driverId: number; name: string }>(`/api/drivers/${id}`, { name, email });
  }

  delete(id: number) {
    return this.http.delete<void>(`/api/drivers/${id}`);
  }

  associateSession(driverId: number, sessionUid: string, carIndex: number) {
    return this.http.post<{ driverId: number; sessionUid: string; carIndex: number }>(
      `/api/drivers/${driverId}/sessions`,
      { sessionUid, carIndex },
    );
  }

  removeSession(driverId: number, sessionUid: string) {
    return this.http.delete<void>(`/api/drivers/${driverId}/sessions/${sessionUid}`);
  }
}
