package dev.victormartin.telemetry.engineer.v2.detectors;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import dev.victormartin.telemetry.engineer.EngineerMessage;
import dev.victormartin.telemetry.engineer.EngineerMessage.Priority;
import dev.victormartin.telemetry.engineer.v2.EngineerTick;
import dev.victormartin.telemetry.engineer.v2.PitState;
import dev.victormartin.telemetry.engineer.v2.RadioDetector;
import dev.victormartin.telemetry.engineer.v2.SessionKind;

/**
 * Rain forecast warner. Fires once per dry-to-rain transition window.
 *
 * Ports v1 detectWeatherChange.
 */
public class WeatherDetector implements RadioDetector {

    private final Map<String, State> stateByUid = new ConcurrentHashMap<>();

    @Override
    public String name() { return "Weather"; }

    @Override
    public Set<PitState> appliesToStates() { return Set.of(); }

    @Override
    public Set<SessionKind> appliesToSessions() { return Set.of(); }

    @Override
    public Optional<EngineerMessage> evaluate(EngineerTick tick) {
        State s = stateByUid.computeIfAbsent(tick.sessionUid(), k -> new State());
        int weather = tick.state().has("weather") ? tick.state().get("weather").asInt() : 0;

        Optional<EngineerMessage> out = Optional.empty();
        if (weather == 0 && !s.alertSent) {
            JsonNode forecast = tick.state().get("forecast");
            if (forecast != null && forecast.isArray()) {
                for (JsonNode sample : forecast) {
                    int rain = sample.has("rain") ? sample.get("rain").asInt() : 0;
                    int offset = sample.has("offset") ? sample.get("offset").asInt() : 0;
                    if (rain > 30 && offset > 0) {
                        out = Optional.of(new EngineerMessage(
                                Priority.NORMAL,
                                "Rain expected in " + offset + " minutes. Stay out for now.",
                                tick.wallClockMs(), tick.currentLap(), 3));
                        s.alertSent = true;
                        break;
                    }
                }
            }
        }

        if (s.previousWeather >= 0 && weather != s.previousWeather) {
            s.alertSent = false;
        }
        s.previousWeather = weather;
        return out;
    }

    @Override
    public void onSessionStarted(String sessionUid, int trackId, int sessionType) {
        stateByUid.put(sessionUid, new State());
    }

    @Override
    public void onSessionEnded(String sessionUid) {
        stateByUid.remove(sessionUid);
    }

    private static class State {
        int previousWeather = -1;
        boolean alertSent = false;
    }
}
