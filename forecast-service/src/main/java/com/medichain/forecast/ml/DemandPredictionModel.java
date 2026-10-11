package com.medichain.forecast.ml;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The demand forecasting model. It is an ENSEMBLE (blend) of two models:
 *
 *  Model A - time series (Holt's exponential smoothing, damped trend):
 *      learns the current level and trend of daily demand at this facility.
 *      level = alpha*today + (1-alpha)*(previous level + trend)
 *      trend = beta*(level change) + (1-beta)*previous trend
 *      alpha and beta are LEARNED by trying many values and keeping the most accurate.
 *      A weekday pattern (e.g. Sundays are quiet) is removed before and added back after.
 *
 *  Model B - disease regression:
 *      weekly demand = a + b * weekly district cases   (least squares, gives R squared)
 *      The disease case curve is projected forward (also with Holt), so an outbreak that is
 *      starting to rise pushes the forecast up BEFORE consumption history shows it.
 *
 *  Final forecast = (1 - w) * A + w * B,  where  w = 0.6 * R squared  (0 if R squared &lt; 0.3).
 *  So the disease model only counts when cases really explain this medicine's demand.
 */
public final class DemandPredictionModel {

    private DemandPredictionModel() { }

    /** Output of the model for one facility x medicine. */
    public record Prediction(double[] daily,          // forecast for day 0..n-1 (day 0 = asOf)
                             double[] baselineDaily,  // pure time-series forecast without seasonal disease regression
                             double errorStd,         // typical daily error (from training residuals)
                             double alpha, double beta,
                             double r2, double diseaseWeight,
                             double casesChangeLast2WeeksPct, double casesChangeNext2WeeksPct,
                             double avgLast14, double avgPrev14) {

        public Prediction(double[] daily, double errorStd, double alpha, double beta,
                          double r2, double diseaseWeight,
                          double casesChangeLast2WeeksPct, double casesChangeNext2WeeksPct,
                          double avgLast14, double avgPrev14) {
            this(daily, daily, errorStd, alpha, beta, r2, diseaseWeight, casesChangeLast2WeeksPct, casesChangeNext2WeeksPct, avgLast14, avgPrev14);
        }

        public double total(int days) {
            double s = 0;
            for (int i = 0; i < Math.min(days, daily.length); i++) s += daily[i];
            return s;
        }

        public double average(int days) {
            return total(days) / Math.max(1, Math.min(days, daily.length));
        }

        public double baselineTotal(int days) {
            double s = 0;
            for (int i = 0; i < Math.min(days, baselineDaily.length); i++) s += baselineDaily[i];
            return s;
        }

        public double baselineAverage(int days) {
            return baselineTotal(days) / Math.max(1, Math.min(days, baselineDaily.length));
        }
    }

    /**
     * @param historyStart date of demand[0]
     * @param demand       daily demand, one value per day up to the day before asOf
     * @param weeklyCases  weekStart -> district cases of the medicine's disease (empty map if none)
     * @param asOf         first day to forecast
     * @param days         how many days to forecast
     */
    public static Prediction predict(LocalDate historyStart, double[] demand, Map<LocalDate, Integer> weeklyCases,
                                     LocalDate asOf, int days) {
        int n = demand.length;

        // ---- weekday pattern from the last 8 weeks (average = 1.0)
        double[] weekday = new double[7];
        double[] count = new double[7];
        double sum = 0;
        int from = Math.max(0, n - 56);
        for (int i = from; i < n; i++) {
            int d = dow(historyStart.plusDays(i));
            weekday[d] += demand[i];
            count[d]++;
            sum += demand[i];
        }
        double mean = sum / Math.max(1, n - from);
        for (int d = 0; d < 7; d++) {
            weekday[d] = (count[d] > 0 && mean > 0) ? Math.max(0.05, weekday[d] / count[d] / mean) : 1.0;
        }

        // ---- Model A: Holt on weekday-adjusted demand
        double[] adjusted = new double[n];
        for (int i = 0; i < n; i++) adjusted[i] = demand[i] / weekday[dow(historyStart.plusDays(i))];
        Holt holt = Holt.fit(adjusted);

        // ---- Model B: regression of weekly demand on weekly cases
        double a = 0, b = 0, r2 = 0, changeLast = 0, changeNext = 0;
        double[] futureCases = null;
        LocalDate lastWeek = null;
        TreeMap<LocalDate, Integer> weeks = new TreeMap<>();
        weeklyCases.forEach((week, cases) -> { if (week.plusDays(6).isBefore(asOf)) weeks.put(week, cases); }); // complete weeks only
        List<double[]> points = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> e : weeks.entrySet()) {
            long offset = ChronoUnit.DAYS.between(historyStart, e.getKey());
            if (offset < 0 || offset + 7 > n) continue;
            double weekDemand = 0;
            for (int k = 0; k < 7; k++) weekDemand += demand[(int) offset + k];
            points.add(new double[]{e.getValue(), weekDemand});
        }
        if (points.size() >= 6) {
            double[] fit = leastSquares(points);
            a = fit[0];
            b = fit[1];
            r2 = fit[2];
        }
        if (weeks.size() >= 4) {
            double[] c = weeks.values().stream().mapToDouble(Integer::doubleValue).toArray();
            int m = c.length;
            double last2 = (c[m - 1] + c[m - 2]) / 2, prev2 = (c[m - 3] + c[m - 4]) / 2;
            Holt caseModel = Holt.fit(c);
            futureCases = new double[days / 7 + 4];
            for (int k = 1; k < futureCases.length; k++) futureCases[k] = caseModel.forecast(k);
            double next2 = (futureCases[1] + futureCases[2]) / 2;
            changeLast = prev2 > 0 ? 100 * (last2 - prev2) / prev2 : 0;
            changeNext = last2 > 0 ? 100 * (next2 - last2) / last2 : 0;
            lastWeek = weeks.lastKey();
        }
        double w = (r2 >= 0.3 && b > 0 && futureCases != null) ? 0.6 * r2 : 0;

        // ---- blend A and B day by day
        double[] out = new double[days];
        double[] baseOut = new double[days];
        for (int h = 0; h < days; h++) {
            LocalDate day = asOf.plusDays(h);
            double modelA = Math.max(0, holt.forecast(h + 1) * weekday[dow(day)]);
            baseOut[h] = modelA;
            double value = modelA;
            if (w > 0) {
                int k = (int) Math.max(1, ChronoUnit.WEEKS.between(lastWeek, day.with(DayOfWeek.MONDAY)));
                k = Math.min(k, futureCases.length - 1);
                double modelB = Math.max(0, a + b * futureCases[k]) / 7 * weekday[dow(day)];
                value = (1 - w) * modelA + w * modelB;
            }
            out[h] = Math.max(0, value);
        }
        return new Prediction(out, baseOut, holt.errorStd, holt.alpha, holt.beta, r2, w, changeLast, changeNext,
                average(demand, n - 14, n), average(demand, n - 28, n - 14));
    }

    // ------------------------------------------------------------------ Holt's damped-trend smoothing

    /** Holt model: fitted level, trend and learned parameters. */
    static final class Holt {
        static final double PHI = 0.9; // trend damping so long forecasts don't explode
        double alpha, beta, level, trend, errorStd, sse;

        double forecast(int h) {
            double damp = 0, p = 1;
            for (int i = 1; i <= h; i++) { p *= PHI; damp += p; }
            return Math.max(0, level + damp * trend);
        }

        /** "Training": try every alpha/beta pair and keep the one with the smallest one-step-ahead error. */
        static Holt fit(double[] y) {
            Holt best = null;
            for (double alpha : new double[]{0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8}) {
                for (double beta : new double[]{0.01, 0.05, 0.1, 0.2, 0.3}) {
                    Holt h = run(y, alpha, beta);
                    if (best == null || h.sse < best.sse) best = h;
                }
            }
            return best;
        }

        private static Holt run(double[] y, double alpha, double beta) {
            Holt h = new Holt();
            h.alpha = alpha;
            h.beta = beta;
            if (y.length < 4) {
                h.level = average(y, 0, y.length);
                h.errorStd = Math.max(1, h.level * 0.25);
                return h;
            }
            int init = Math.min(7, y.length / 2);
            h.level = average(y, 0, init);
            h.trend = (average(y, init, Math.min(y.length, 2 * init)) - h.level) / init;
            int count = 0;
            for (int t = init; t < y.length; t++) {
                double error = y[t] - (h.level + PHI * h.trend);
                h.sse += error * error;
                count++;
                double newLevel = alpha * y[t] + (1 - alpha) * (h.level + PHI * h.trend);
                h.trend = beta * (newLevel - h.level) + (1 - beta) * PHI * h.trend;
                h.level = newLevel;
            }
            h.errorStd = count > 1 ? Math.sqrt(h.sse / (count - 1)) : 0;
            return h;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Ordinary least squares y = a + b*x. Returns {a, b, R squared}. */
    static double[] leastSquares(List<double[]> xy) {
        double mx = 0, my = 0;
        for (double[] p : xy) { mx += p[0]; my += p[1]; }
        mx /= xy.size();
        my /= xy.size();
        double sxy = 0, sxx = 0, syy = 0;
        for (double[] p : xy) {
            sxy += (p[0] - mx) * (p[1] - my);
            sxx += (p[0] - mx) * (p[0] - mx);
            syy += (p[1] - my) * (p[1] - my);
        }
        if (sxx == 0 || syy == 0) return new double[]{my, 0, 0};
        double b = sxy / sxx;
        return new double[]{my - b * mx, b, sxy * sxy / (sxx * syy)};
    }

    private static int dow(LocalDate d) {
        return d.getDayOfWeek().getValue() - 1;
    }

    private static double average(double[] y, int from, int to) {
        from = Math.max(0, from);
        if (to <= from) return 0;
        double s = 0;
        for (int i = from; i < to; i++) s += y[i];
        return s / (to - from);
    }
}
