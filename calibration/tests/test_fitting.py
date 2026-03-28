import numpy as np
import pytest

from calibration.fitting import linear_regression, mean, variance


class TestLinearRegression:

    def test_perfect_line(self):
        x = np.array([1.0, 2, 3, 4, 5])
        y = np.array([2.0, 4, 6, 8, 10])
        r = linear_regression(x, y)

        assert r.slope == pytest.approx(2.0, abs=1e-9)
        assert r.intercept == pytest.approx(0.0, abs=1e-9)
        assert r.r_squared == pytest.approx(1.0, abs=1e-9)
        assert r.slope_std_error == pytest.approx(0.0, abs=1e-9)
        assert r.n == 5

    def test_with_offset(self):
        x = np.array([1.0, 2, 3, 4, 5])
        y = np.array([103.0, 106, 109, 112, 115])  # y = 100 + 3x
        r = linear_regression(x, y)

        assert r.slope == pytest.approx(3.0, abs=1e-9)
        assert r.intercept == pytest.approx(100.0, abs=1e-9)
        assert r.r_squared == pytest.approx(1.0, abs=1e-9)

    def test_with_noise(self):
        x = np.array([1.0, 2, 3, 4, 5, 6, 7, 8, 9, 10])
        y = np.array([1052.0, 1098, 1153, 1199, 1248, 1302, 1351, 1398, 1452, 1501])
        r = linear_regression(x, y)

        assert r.slope == pytest.approx(50.0, abs=2.0)
        assert r.r_squared > 0.99
        assert r.slope_std_error > 0
        assert r.n == 10

    def test_constant_x(self):
        x = np.array([5.0, 5, 5, 5])
        y = np.array([10.0, 20, 30, 40])
        r = linear_regression(x, y)

        assert r.slope == 0.0
        assert r.r_squared == 0.0


class TestMeanVariance:

    def test_mean(self):
        values = np.array([10.0, 20, 30, 40, 50])
        assert mean(values) == pytest.approx(30.0, abs=1e-9)

    def test_variance(self):
        values = np.array([10.0, 20, 30, 40, 50])
        assert variance(values) == pytest.approx(200.0, abs=1e-9)
