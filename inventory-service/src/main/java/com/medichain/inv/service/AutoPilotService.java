package com.medichain.inv.service;

import com.medichain.inv.dto.ForecastResponse;
import com.medichain.inv.dto.ForecastResponse.OrderRow;
import com.medichain.inv.dto.ForecastResponse.TransferRow;
import com.medichain.inv.dto.StockDtos.CreatePurchaseOrder;
import com.medichain.inv.dto.StockDtos.TransferApproval;
import com.medichain.inv.entity.Facility;
import com.medichain.inv.entity.Medicine;
import com.medichain.inv.entity.PurchaseOrder;
import com.medichain.inv.entity.Transfer;
import com.medichain.inv.repository.FacilityRepository;
import com.medichain.inv.repository.MedicineRepository;
import com.medichain.inv.repository.PurchaseOrderRepository;
import com.medichain.inv.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Autonomous Supply Chain Auto-Pilot:
 * Continuously evaluates predicted stock-outs and near-expiry batches.
 * When a medicine is going to finish:
 *   1. It automatically initiates an inter-facility transfer from the nearest surplus facility.
 *   2. If no nearby surplus exists, it automatically places a purchase order with the supplier.
 * When a batch is nearing expiry:
 *   - The transfer engine prioritizes moving it to high-demand facilities to prevent wastage.
 */
@Service
public class AutoPilotService {

    public record AutoPilotAction(
            String id,
            String type,            // "AUTO_TRANSFER" or "AUTO_PROCUREMENT"
            String district,
            String facility,
            String medicine,
            int quantity,
            String rationale,
            LocalDateTime timestamp
    ) {}

    public record AutoPilotSummary(
            String district,
            boolean success,
            int transfersCreated,
            int transfersCompleted,
            int ordersPlaced,
            int stockoutsPrevented,
            int expiringUnitsRescued,
            double totalOrderCostInr,
            List<String> actionLogs
    ) {}

    private final QueryService queryService;
    private final StockService stockService;
    private final FacilityRepository facilityRepo;
    private final MedicineRepository medicineRepo;
    private final TransferRepository transferRepo;
    private final PurchaseOrderRepository purchaseOrderRepo;

    @Value("${autopilot.enabled:true}")
    private boolean enabled;

    private final List<AutoPilotAction> auditHistory = Collections.synchronizedList(new ArrayList<>());

    public AutoPilotService(QueryService queryService, StockService stockService,
                            FacilityRepository facilityRepo, MedicineRepository medicineRepo,
                            TransferRepository transferRepo, PurchaseOrderRepository purchaseOrderRepo) {
        this.queryService = queryService;
        this.stockService = stockService;
        this.facilityRepo = facilityRepo;
        this.medicineRepo = medicineRepo;
        this.transferRepo = transferRepo;
        this.purchaseOrderRepo = purchaseOrderRepo;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<AutoPilotAction> getAuditHistory() {
        synchronized (auditHistory) {
            return new ArrayList<>(auditHistory);
        }
    }

    /**
     * Executes the autonomous rebalancing and procurement policy for a district.
     */
    @Transactional
    public AutoPilotSummary execute(String district) {
        List<String> logs = new ArrayList<>();
        int transfersCreated = 0;
        int transfersCompleted = 0;
        int ordersPlaced = 0;
        int stockoutsPrevented = 0;
        int expiringUnitsRescued = 0;
        double totalCost = 0.0;

        try {
            // 1. Run AI analysis across all medicines for the next 14-day horizon
            ForecastResponse ai = queryService.analyse(district, List.of(), 14);

            // 2. Automated Inter-Facility Transfers (Redistribution)
            for (TransferRow t : ai.transfers()) {
                // Check if a transfer is already pending for this destination & medicine
                List<Transfer> pending = transferRepo.findByToFacilityIdAndMedicineIdAndStatusIn(
                        t.toFacilityId(), t.medicineId(), List.of("APPROVED", "AUTO_APPROVED")
                );
                if (!pending.isEmpty()) {
                    continue; // Skip already addressed deficit
                }

                // Create and auto-approve the transfer
                TransferApproval req = new TransferApproval(
                        t.fromFacilityId(), t.toFacilityId(), t.medicineId(),
                        t.quantity(), t.distanceKm(),
                        "[Auto-Pilot] " + t.rationale()
                );
                Transfer saved = stockService.approveTransfer(req);
                saved.status = "AUTO_APPROVED";
                transferRepo.save(saved);
                transfersCreated++;

                // Automatically execute the transfer to rebalance inventory immediately
                try {
                    stockService.completeTransfer(saved.id);
                    transfersCompleted++;
                    stockoutsPrevented++;
                    expiringUnitsRescued += t.expiringUnitsRescued();

                    String logMsg = String.format("Auto-transferred %,d units of %s from %s to %s (%.0f km) — Rescued %,d expiring units.",
                            t.quantity(), t.medicineName(), t.fromFacility(), t.toFacility(), t.distanceKm(), t.expiringUnitsRescued());
                    logs.add(logMsg);

                    recordAction("AUTO_TRANSFER", district, t.toFacility(), t.medicineName(), t.quantity(), logMsg);
                } catch (Exception ex) {
                    logs.add("Transfer #" + saved.id + " approved for physical transit: " + ex.getMessage());
                }
            }

            // 3. Automated Procurement Purchase Orders (for deficits that cannot be met by transfers)
            for (OrderRow o : ai.orders()) {
                // Check if an order is already placed for this facility & medicine
                List<PurchaseOrder> activeOrders = purchaseOrderRepo.findByFacilityIdAndMedicineIdAndStatusIn(
                        o.facilityId(), o.medicineId(), List.of("ORDERED", "AUTO_ORDERED")
                );
                if (!activeOrders.isEmpty()) {
                    continue; // Already on order
                }

                // Place the automated purchase order
                CreatePurchaseOrder poReq = new CreatePurchaseOrder(
                        o.facilityId(), o.medicineId(), o.orderQuantity(),
                        o.leadTimeDays(), o.orderBy(),
                        (o.orderQuantity() > 0 ? o.costInr() / o.orderQuantity() : 1.0),
                        o.costInr(), o.urgency()
                );
                PurchaseOrder po = stockService.createPurchaseOrder(poReq);
                po.status = "AUTO_ORDERED";
                purchaseOrderRepo.save(po);

                ordersPlaced++;
                stockoutsPrevented++;
                totalCost += o.costInr();

                String logMsg = String.format("Auto-ordered %,d units of %s for %s (Urgency: %s, Arrival by: %s, Total: ₹%,.0f) — Rationale: %s",
                        o.orderQuantity(), o.medicineName(), o.facilityName(), o.urgency(), o.orderBy(), o.costInr(), o.reason());
                logs.add(logMsg);

                recordAction("AUTO_PROCUREMENT", district, o.facilityName(), o.medicineName(), o.orderQuantity(), logMsg);
            }

            return new AutoPilotSummary(
                    district, true, transfersCreated, transfersCompleted, ordersPlaced,
                    stockoutsPrevented, expiringUnitsRescued, totalCost, logs
            );

        } catch (Exception e) {
            logs.add("Auto-Pilot evaluation encountered error: " + e.getMessage());
            return new AutoPilotSummary(
                    district, false, transfersCreated, transfersCompleted, ordersPlaced,
                    stockoutsPrevented, expiringUnitsRescued, totalCost, logs
            );
        }
    }

    /**
     * Runs Auto-Pilot across all registered districts in the state.
     */
    public List<AutoPilotSummary> executeAll() {
        List<AutoPilotSummary> summaries = new ArrayList<>();
        for (String district : facilityRepo.findDistricts()) {
            summaries.add(execute(district));
        }
        return summaries;
    }

    /**
     * Periodic scheduled watchdog: runs every 5 minutes in background.
     */
    @Scheduled(fixedDelayString = "${autopilot.interval-ms:300000}", initialDelay = 30000)
    public void backgroundAutoPilotWatchdog() {
        if (!enabled) return;
        try {
            executeAll();
        } catch (Exception e) {
            System.err.println("AutoPilot background run warning: " + e.getMessage());
        }
    }

    private void recordAction(String type, String district, String facility, String medicine, int quantity, String rationale) {
        synchronized (auditHistory) {
            if (auditHistory.size() >= 100) {
                auditHistory.remove(0);
            }
            auditHistory.add(new AutoPilotAction(
                    UUID.randomUUID().toString().substring(0, 8),
                    type, district, facility, medicine, quantity, rationale, LocalDateTime.now()
            ));
        }
    }
}
