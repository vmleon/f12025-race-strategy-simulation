from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from sklearn.linear_model import LinearRegression


@dataclass(frozen=True)
class RegressionResult:
    slope: float
    intercept: float
    r_squared: float
    slope_std_error: float
    n: int


def linear_regression(x: np.ndarray, y: np.ndarray) -> RegressionResult:
    """Fits y = slope * x + intercept. Returns slope, intercept, R², slope SE, and n."""
    n = len(x)
    ss_xx = np.sum((x - x.mean()) ** 2)

    if ss_xx == 0:
        return RegressionResult(slope=0.0, intercept=float(y.mean()), r_squared=0.0, slope_std_error=0.0, n=n)

    model = LinearRegression().fit(x.reshape(-1, 1), y)
    slope = float(model.coef_[0])
    intercept = float(model.intercept_)

    ss_yy = np.sum((y - y.mean()) ** 2)
    ss_xy = np.sum((x - x.mean()) * (y - y.mean()))
    r_squared = float((ss_xy ** 2) / (ss_xx * ss_yy)) if ss_yy > 0 else 0.0

    residual_variance = float((ss_yy - slope * ss_xy) / (n - 2)) if n > 2 else 0.0
    slope_std_error = float(np.sqrt(residual_variance / ss_xx)) if residual_variance > 0 else 0.0

    return RegressionResult(slope=slope, intercept=intercept, r_squared=r_squared,
                            slope_std_error=slope_std_error, n=n)


def mean(values: np.ndarray) -> float:
    return float(np.mean(values))


def variance(values: np.ndarray) -> float:
    """Population variance (ddof=0)."""
    return float(np.var(values, ddof=0))
