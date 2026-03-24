import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { SimulationResult } from './race.service';

@Injectable({ providedIn: 'root' })
export class SimulationService {
  constructor(private http: HttpClient) {}

  trigger() {
    return this.http.post<{ jobId: string; status: string }>('/api/simulation/trigger', null);
  }

  getResult(jobId: string) {
    return this.http.get<SimulationResult | { jobId: string; status: string }>(
      `/api/simulation/results/${jobId}`
    );
  }
}
