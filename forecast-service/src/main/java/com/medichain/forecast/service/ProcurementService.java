package com.medichain.forecast.service;

import com.medichain.forecast.config.ForecastConfig;
import com.medichain.forecast.dto.ForecastResponse.OrderRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Procurement = when to reorder and how much (classic reorder-point method, using the AI forecast).
 *
 *   safety stock  = z x daily forecast error x sqrt(lead time)
 *   reorder point = forecast per day x lead time + safety stock
 *   order qty     = forecast for (lead time + 7-day review + 30-day cycle) + safety stock - current stock
 *
 * Stock counted here already includes the recommended transfers and excludes units that will expire.
 */
@Service
public class ProcurementService {

    private final ForecastConfig config;

    public ProcurementService(ForecastConfig config) {
        this.config = config;
    }

    /** Returns an order suggestion, or null if no order is needed yet. */
    public OrderRow suggest(FacilityMedicine it, LocalDate asOf) {
        int lead = it.medicine.leadTimeDays();
        double perDay = it.prediction.average(30);
        if (perDay < 0.01) return null;

        int safety = (int) Math.ceil(config.serviceLevelZ * it.dailyErrorStd() * Math.sqrt(lead));
        int reorderPoint = (int) Math.ceil(perDay * lead + safety);
        int expiringNotRescued = Math.max(0, it.totalExpiring() - it.expiringRescued);
        int stock = Math.max(0, it.usableStock + it.incoming - it.outgoing - expiringNotRescued);
        if (stock > reorderPoint + perDay * 7) return null;          // fine for at least another week

        int qty = (int) Math.ceil((perDay * (lead + 7 + 30) + safety - stock) / 50.0) * 50;
        double daysLeft = stock / perDay;
        LocalDate orderBy = asOf.plusDays((long) Math.max(0, Math.floor(daysLeft - lead - safety / perDay)));

        String urgency, reason;
        if (daysLeft < lead) {
            urgency = "URGENT";
            reason = String.format("Only %.0f days of stock after transfers, less than the %d-day lead time. "
                    + "Order today and ask for express supply.", daysLeft, lead);
        } else if (!orderBy.isAfter(asOf.plusDays(7))) {
            urgency = "THIS WEEK";
            reason = String.format("Stock %,d is close to the reorder point %,d (%.0f/day x %d days + %,d safety stock).",
                    stock, reorderPoint, perDay, lead, safety);
        } else {
            urgency = "PLANNED";
            reason = String.format("Reorder point %,d will be reached around %s.", reorderPoint, orderBy);
        }
        return new OrderRow(it.facility.id(), it.facility.name(), it.medicine.id(), it.medicine.name(), stock,
                StockOutRiskService.round1(perDay), lead, safety, reorderPoint, qty, orderBy, urgency,
                Math.round(qty * it.medicine.unitPrice()), reason);
    }
}
