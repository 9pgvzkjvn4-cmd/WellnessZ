package com.wellnessz.e31.terminology;

import java.util.Comparator;
import java.util.List;

/**
 * GLI-2012 spirometry reference equations (Quanjer et al., Eur Respir J 2012) – LMS method:
 * <pre>
 *   L = l0 + l1·ln(age)
 *   M = exp(m0 + m1·ln(height cm) + m2·ln(age) + Mspline(age))
 *   S = exp(s0 + s1·ln(age) + Sspline(age))
 *   LLN = M·(1 − 1.645·L·S)^(1/L)        z = ((observed / M)^L − 1) / (L·S)
 * </pre>
 * Coefficients and the spline lookup come from the {@code deviceint.predicted_equation} / {@code predicted_spline} tables.
 */
public final class Gli2012 {

    public static final double Z_LLN = -1.645;

    private Gli2012() {
    }

    public record SplinePoint(double age, double mSpline, double sSpline) {
    }

    public record Coefficients(String parameter, String sex, double l0, double l1, double m0, double m1, double m2, double s0, double s1,
                               List<SplinePoint> splines) {

        public Coefficients {
            splines = splines == null ? List.of() : splines.stream().sorted(Comparator.comparingDouble(SplinePoint::age)).toList();
        }
    }

    /** @param predicted M (litres for FEV1 / FVC, a fraction for FEV1/FVC) */
    public record Prediction(double predicted, double lln, double l, double s) {

        public double zScore(double observed) {
            return (Math.pow(observed / predicted, l) - 1.0) / (l * s);
        }

        public double percentPredicted(double observed) {
            return observed / predicted * 100.0;
        }
    }

    public static Prediction predict(Coefficients c, double ageYears, double heightCm) {
        if (ageYears < 3 || ageYears > 95 || heightCm < 90 || heightCm > 230) {
            throw new IllegalArgumentException("GLI-2012 is defined for ages 3–95 years and plausible heights");
        }
        double lnAge = Math.log(ageYears);
        double[] spl = spline(c.splines(), ageYears);
        double l = c.l0() + c.l1() * lnAge;
        double m = Math.exp(c.m0() + c.m1() * Math.log(heightCm) + c.m2() * lnAge + spl[0]);
        double s = Math.exp(c.s0() + c.s1() * lnAge + spl[1]);
        double lln = m * Math.pow(1.0 + Z_LLN * l * s, 1.0 / l);
        return new Prediction(m, lln, l, s);
    }

    /** Linear interpolation of the spline lookup table; constant beyond both ends. */
    static double[] spline(List<SplinePoint> points, double age) {
        if (points.isEmpty()) {
            return new double[] {0, 0};
        }
        SplinePoint first = points.get(0);
        if (age <= first.age()) {
            return new double[] {first.mSpline(), first.sSpline()};
        }
        for (int i = 1; i < points.size(); i++) {
            SplinePoint b = points.get(i);
            if (age <= b.age()) {
                SplinePoint a = points.get(i - 1);
                double t = (age - a.age()) / (b.age() - a.age());
                return new double[] {a.mSpline() + t * (b.mSpline() - a.mSpline()), a.sSpline() + t * (b.sSpline() - a.sSpline())};
            }
        }
        SplinePoint last = points.get(points.size() - 1);
        return new double[] {last.mSpline(), last.sSpline()};
    }
}
