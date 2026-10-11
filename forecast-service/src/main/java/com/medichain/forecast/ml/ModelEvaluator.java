package com.medichain.forecast.ml;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

/**
 * Measures how accurate the model is with a BACKTEST:
 * pretend "today" was 14 days ago, forecast those 14 days, and compare with what really happened.
 */
public final class ModelEvaluator {

    private ModelEvaluator() { }

    /** Error of the 14-day total in percent, or null if there is not enough history. */
    public static Double backtestErrorPct(LocalDate historyStart, double[] demand, Map<LocalDate, Integer> weeklyCases,
                                          LocalDate asOf) {
        int hold = 14;
        if (demand.length < 42) return null;
        double[] train = Arrays.copyOf(demand, demand.length - hold);
        DemandPredictionModel.Prediction p =
                DemandPredictionModel.predict(historyStart, train, weeklyCases, asOf.minusDays(hold), hold);
        double actual = 0;
        for (int i = demand.length - hold; i < demand.length; i++) actual += demand[i];
        if (actual <= 0) return null;
        return Math.round(1000 * Math.abs(p.total(hold) - actual) / actual) / 10.0;
    }
}
