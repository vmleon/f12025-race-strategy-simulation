import os

os.environ["SIMULATOR_USE_DB"] = "false"

from fastapi.testclient import TestClient

from simulator.app import app

client = TestClient(app)


def _sample_snapshot() -> dict:
    return {
        "trackId": 0,
        "totalLaps": 5,
        "currentLap": 1,
        "currentSector": 0,
        "weather": 0,
        "trackTemp": 35,
        "airTemp": 25,
        "safetyCar": False,
        "cars": [
            {
                "carIndex": 0,
                "driverName": "Hamilton",
                "aiControlled": True,
                "position": 1,
                "tyreCompound": 17,
                "tyreAgeLaps": 0,
                "fuelKg": 80.0,
                "fuelBurnPerSectorKg": 0.18,
                "frontWingDamage": 0,
                "floorDamage": 0,
                "engineDamage": 0,
                "numPitStops": 0,
                "totalTimeMs": 0.0,
            },
            {
                "carIndex": 1,
                "driverName": "Verstappen",
                "aiControlled": True,
                "position": 2,
                "tyreCompound": 17,
                "tyreAgeLaps": 0,
                "fuelKg": 80.0,
                "fuelBurnPerSectorKg": 0.18,
                "frontWingDamage": 0,
                "floorDamage": 0,
                "engineDamage": 0,
                "numPitStops": 0,
                "totalTimeMs": 0.0,
            },
        ],
        "pitStrategy": None,
    }


class TestHealth:
    def test_health(self):
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json() == {"status": "ok"}


class TestSimulate:
    def test_simulate_returns_results(self):
        resp = client.post("/simulate", json=_sample_snapshot())
        assert resp.status_code == 200
        data = resp.json()
        assert "iterations" in data
        assert "converged" in data
        assert "wallClockMs" in data
        assert len(data["cars"]) == 2

    def test_simulate_car_result_fields(self):
        resp = client.post("/simulate", json=_sample_snapshot())
        car = resp.json()["cars"][0]
        assert "carIndex" in car
        assert "driverName" in car
        assert "meanPosition" in car
        assert "positionStdDev" in car
        assert "ci95Low" in car
        assert "ci95High" in car
        assert "dnfProbability" in car
        assert "top3Probability" in car
        assert "pointsFinishProbability" in car
        assert "positionDistribution" in car


class TestEvaluateStrategies:
    def test_evaluate_strategies(self):
        payload = {
            "snapshot": _sample_snapshot(),
            "playerCarIndex": 0,
            "candidates": [
                {"label": "No stop", "stops": []},
                {
                    "label": "1-stop",
                    "stops": [{"onLap": 3, "newCompound": 18}],
                },
            ],
        }
        # Mark car 0 as player
        payload["snapshot"]["cars"][0]["aiControlled"] = False

        resp = client.post("/evaluate-strategies", json=payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["playerCarIndex"] == 0
        assert len(data["strategies"]) == 2
        assert data["strategies"][0]["rank"] == 1
        assert data["strategies"][1]["rank"] == 2

    def test_evaluate_no_candidates_400(self):
        payload = {
            "snapshot": _sample_snapshot(),
            "playerCarIndex": 0,
            "candidates": [],
        }
        resp = client.post("/evaluate-strategies", json=payload)
        assert resp.status_code == 400
