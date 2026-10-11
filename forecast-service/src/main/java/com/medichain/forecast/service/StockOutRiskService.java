package com.medichain.forecast.service;

import com.medichain.forecast.dto.ForecastRequest.BatchData;
import com.medichain.forecast.dto.ForecastResponse.RiskRow;
import com.medichain.forecast.ml.DemandPredictionModel.Prediction;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Stock-out risk = "when will this facility run out, and how likely is it within the horizon?"
 *
 * Days-to-stock-out: simulate day by day - every morning throw away expired batches,
 * then use the forecast demand from the batch that expires first (FEFO).
 * The first day demand can't be met is the stock-out day.
 *
 * Probability: forecast demand over the horizon is treated as a normal distribution
 * (mean = forecast, spread = model error), compared with the usable stock.
 */
@Service
public class StockOutRiskService {

    public static final int SIMULATION_DAYS = 180;

    /** Runs the stock simulation and stores days-to-stock-out and expiring units on the item. */
    public void simulate(FacilityMedicine item, LocalDate asOf) {
        Result r = simulate(item.batches, item.prediction.daily(), asOf);
        item.daysToStockout = r.daysToStockout();
        item.usableStock = r.usableStock();
        item.unitsExpiring = r.unitsExpiring();
    }

    public record Result(Integer daysToStockout, int usableStock, Map<String, Integer> unitsExpiring) { }

    /** Day-by-day FEFO simulation of the batches against a daily demand forecast. */
    public static Result simulate(List<BatchData> batches, double[] dailyDemand, LocalDate asOf) {
        List<BatchData> sorted = new ArrayList<>(batches);
        sorted.sort(Comparator.comparing(BatchData::expiryDate));
        List<String> names = new ArrayList<>();
        List<double[]> stock = new ArrayList<>();   // {quantity left, expiry day}
        int usable = 0;
        for (BatchData b : sorted) {
            if (b.quantity() <= 0 || b.expiryDate().isBefore(asOf)) continue;   // empty or already expired
            names.add(b.batchNo());
            stock.add(new double[]{b.quantity(), b.expiryDate().toEpochDay()});
            usable += b.quantity();
        }
        Map<String, Integer> expiring = new LinkedHashMap<>();
        Integer stockoutDay = null;
        for (int d = 0; d < dailyDemand.length; d++) {
            long today = asOf.plusDays(d).toEpochDay();
            for (int i = 0; i < stock.size(); i++) {               // 1. expired batches are wasted
                double[] s = stock.get(i);
                if (s[0] > 0 && s[1] < today) {
                    expiring.merge(names.get(i), (int) Math.round(s[0]), Integer::sum);
                    s[0] = 0;
                }
            }
            double need = dailyDemand[d];                           // 2. use stock, first expiry first
            for (double[] s : stock) {
                double take = Math.min(need, s[0]);
                s[0] -= take;
                need -= take;
            }
            if (need > 0.5 && stockoutDay == null) stockoutDay = d;
        }
        return new Result(stockoutDay, usable, expiring);
    }

    /** Builds the risk row (numbers + key factors) for the UI. */
    public RiskRow assess(FacilityMedicine item, int horizon, LocalDate asOf, String district) {
        Prediction p = item.prediction;
        Integer dts = item.daysToStockout;
        int lead = item.medicine.leadTimeDays();

        // chance that demand in the horizon is larger than the stock that is still usable in the horizon
        double mean = p.total(horizon);
        double sd = item.dailyErrorStd() * Math.sqrt(horizon);
        double stock = item.usableStock - expiringBefore(item, asOf.plusDays(horizon));
        double chance = sd > 0 ? 1 - normalCdf((stock - mean) / sd) : (stock < mean ? 1 : 0);
        if (dts != null && dts < horizon) chance = Math.max(chance, 0.5);

        String level = dts == null || dts > lead + 14 ? "LOW"
                : dts <= 7 ? "CRITICAL"
                : dts <= lead ? "HIGH" : "MEDIUM";

        // ---- explanation
        String unit = item.medicine.unit();
        List<String> f = new ArrayList<>();
        LocalDate nearestExpiry = item.batches.stream().filter(b -> b.quantity() > 0 && !b.expiryDate().isBefore(asOf))
                .map(BatchData::expiryDate).min(Comparator.naturalOrder()).orElse(null);
        f.add(String.format("Usable stock: %,d %ss%s", item.usableStock, unit,
                nearestExpiry == null ? "" : " (nearest expiry " + nearestExpiry + ")"));
        double trend = p.avgPrev14() > 0 ? 100 * (p.avgLast14() - p.avgPrev14()) / p.avgPrev14() : 0;
        f.add(String.format("Used %.0f/day in the last 14 days (%+.0f%% vs the 14 days before)", p.avgLast14(), trend));
        f.add(String.format("Forecast: %.0f/day for the next %d days (total %,d)", p.average(horizon), horizon, Math.round(mean)));
        if (p.diseaseWeight() > 0) {
            f.add(String.format("%s cases in %s: %+.0f%% in the last 2 weeks, expected %+.0f%% in the next 2 weeks. "
                            + "Demand follows cases closely (R² = %.2f), so the disease model gets %.0f%% weight",
                    disease(item.medicine.diseaseTag()), district, p.casesChangeLast2WeeksPct(),
                    p.casesChangeNext2WeeksPct(), p.r2(), 100 * p.diseaseWeight()));
        } else if ("NONE".equals(item.medicine.diseaseTag())) {
            f.add("Regular-use medicine: no seasonal disease effect, forecast from usage trend only");
        } else {
            f.add(String.format("%s cases checked, but they explain little of the demand here (R² = %.2f)",
                    disease(item.medicine.diseaseTag()), p.r2()));
        }
        if (dts == null) {
            f.add("Stock lasts more than " + SIMULATION_DAYS + " days");
        } else {
            f.add(String.format("Runs out in %d days (%s); a new order takes %d days%s", dts, asOf.plusDays(dts), lead,
                    dts < lead ? " - too late, a transfer is needed" : ""));
        }
        if (item.totalExpiring() > 0) {
            f.add(String.format("%,d units will expire before use (batch %s)", item.totalExpiring(),
                    String.join(", ", item.unitsExpiring.keySet())));
        }

        String model = String.format("%s | alpha=%.2f beta=%.2f | backtest error %s",
                p.diseaseWeight() > 0 ? "Holt + disease regression" : "Holt time series",
                p.alpha(), p.beta(), item.backtestErrorPct == null ? "n/a" : item.backtestErrorPct + "%");

        List<Double> next30 = new ArrayList<>();
        for (int i = 0; i < 30; i++) next30.add(round1(p.daily()[i]));

        return new RiskRow(item.facility.id(), item.facility.name(), item.medicine.id(), item.medicine.name(), unit,
                item.usableStock, round1(p.avgLast14()), round1(p.average(horizon)), dts,
                dts == null ? null : asOf.plusDays(dts), round1(100 * chance), level,
                dts != null && dts <= horizon, f, model, next30);
    }

    /** Units of this item's stock that will expire unused before the given date. */
    public static int expiringBefore(FacilityMedicine item, LocalDate date) {
        int sum = 0;
        for (Map.Entry<String, Integer> e : item.unitsExpiring.entrySet()) {
            LocalDate exp = item.expiryOf(e.getKey());
            if (exp != null && exp.isBefore(date)) sum += e.getValue();
        }
        return sum;
    }

    /** Standard normal cumulative distribution (Abramowitz-Stegun approximation). */
    static double normalCdf(double z) {
        double x = Math.abs(z) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * x);
        double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * Math.exp(-x * x);
        return 0.5 * (1 + Math.signum(z) * erf);
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String disease(String tag) {
        return switch (tag) {
            case "FEVER" -> "Fever";
            case "DIARRHOEA" -> "Diarrhoea";
            case "ARI" -> "Respiratory infection";
            default -> tag;
        };
    }
}
