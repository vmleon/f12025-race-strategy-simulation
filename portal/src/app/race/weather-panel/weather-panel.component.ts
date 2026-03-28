import { Component, Input } from '@angular/core';
import { WeatherForecastSample } from '../../race.service';

const WEATHER_LABELS: Record<number, string> = {
  0: 'Clear',
  1: 'Light Cloud',
  2: 'Overcast',
  3: 'Light Rain',
  4: 'Heavy Rain',
  5: 'Storm',
};

const WEATHER_ICONS: Record<number, string> = {
  0: '\u2600', // sun
  1: '\u26C5', // sun behind cloud
  2: '\u2601', // cloud
  3: '\uD83C\uDF27', // cloud with rain (🌧)
  4: '\uD83C\uDF27', // cloud with rain
  5: '\u26A1', // lightning
};

@Component({
  selector: 'app-weather-panel',
  template: `
    <div class="panel">
      <h3>Weather</h3>
      <div class="current">
        @if (weather != null) {
          <span class="weather-icon">{{ weatherIcon(weather) }}</span>
          <span class="weather-label">{{ weatherLabel(weather) }}</span>
        }
        @if (trackTemp != null) {
          <span class="temp">Track {{ trackTemp }}°C</span>
        }
        @if (airTemp != null) {
          <span class="temp">Air {{ airTemp }}°C</span>
        }
      </div>
      @if (forecast.length > 0) {
        <div class="forecast-timeline">
          @for (f of forecast; track $index) {
            <div class="forecast-item">
              <span class="time-offset">+{{ f.offset }}m</span>
              <span class="forecast-icon">{{ weatherIcon(f.weather) }}</span>
              <span class="forecast-temp">{{ f.trackTemp }}°</span>
              @if (f.rain > 0) {
                <span class="rain">{{ f.rain }}%</span>
              }
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .panel {
      background: #1a1a1a;
      border-radius: 8px;
      padding: 0.75rem 1rem;
    }
    h3 {
      margin: 0 0 0.5rem;
      font-size: 0.9rem;
      color: #999;
      text-transform: uppercase;
    }
    .current {
      display: flex;
      gap: 0.75rem;
      align-items: center;
      flex-wrap: wrap;
    }
    .weather-icon {
      font-size: 1.3rem;
    }
    .weather-label {
      color: #e0e0e0;
      font-weight: bold;
    }
    .temp {
      color: #bbb;
      font-size: 0.85rem;
    }
    .forecast-timeline {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.5rem;
      padding-top: 0.5rem;
      border-top: 1px solid #333;
      overflow-x: auto;
    }
    .forecast-item {
      display: flex;
      flex-direction: column;
      align-items: center;
      min-width: 3rem;
      font-size: 0.75rem;
      color: #bbb;
    }
    .time-offset {
      color: #888;
    }
    .forecast-icon {
      font-size: 1rem;
      margin: 0.15rem 0;
    }
    .forecast-temp {
      font-family: monospace;
    }
    .rain {
      color: #42a5f5;
      font-weight: bold;
    }
  `,
})
export class WeatherPanelComponent {
  @Input() weather: number | null = null;
  @Input() trackTemp: number | null = null;
  @Input() airTemp: number | null = null;
  @Input() forecast: WeatherForecastSample[] = [];

  weatherLabel(w: number): string {
    return WEATHER_LABELS[w] ?? `Weather ${w}`;
  }

  weatherIcon(w: number): string {
    return WEATHER_ICONS[w] ?? '?';
  }
}
