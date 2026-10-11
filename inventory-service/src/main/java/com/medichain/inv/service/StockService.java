package com.medichain.inv.service;

import com.medichain.inv.dto.StockDtos.*;
import com.medichain.inv.entity.*;
import com.medichain.inv.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Stock levels, batches, daily consumption, transfers and the historical dashboard numbers. */
@Service
public class StockService {

    private final FacilityRepository facilityRepo;
    private final MedicineRepository medicineRepo;
    private final StockBatchRepository batchRepo;
    private final ConsumptionRepository consumptionRepo;
    private final TransferRepository transferRepo;
    private final PurchaseOrderRepository purchaseOrderRepo;
    private final DiseaseCaseRepository diseaseCaseRepo;

    public StockService(FacilityRepository facilityRepo, MedicineRepository medicineRepo, StockBatchRepository batchRepo,
                        ConsumptionRepository consumptionRepo, TransferRepository transferRepo,
                        PurchaseOrderRepository purchaseOrderRepo, DiseaseCaseRepository diseaseCaseRepo) {
        this.facilityRepo = facilityRepo;
        this.medicineRepo = medicineRepo;
        this.batchRepo = batchRepo;
        this.consumptionRepo = consumptionRepo;
        this.transferRepo = transferRepo;
        this.purchaseOrderRepo = purchaseOrderRepo;
        this.diseaseCaseRepo = diseaseCaseRepo;
    }

    /**
     * "Today" for the analysis = the day after the last recorded consumption.
     * (Keeps the demo data consistent even if you run it days after seeding.)
     */
    public LocalDate today() {
        return consumptionRepo.findTopByOrderByDateDesc().map(c -> c.date.plusDays(1)).orElse(LocalDate.now());
    }

    // ------------------------------------------------------------------ stock

    public List<StockRow> stockTable(String district) {
        LocalDate today = today();
        List<StockRow> rows = new ArrayList<>();
        List<Facility> facilities = facilityRepo.findByDistrictIgnoreCaseOrderByName(district);
        List<StockBatch> all = batchRepo.findByFacilityIdInAndQuantityGreaterThan(facilities.stream().map(f -> f.id).toList(), 0);
        for (Facility f : facilities) {
            for (Medicine m : medicineRepo.findAll()) {
                int usable = 0, expired = 0, count = 0;
                LocalDate nearest = null;
                for (StockBatch b : all) {
                    if (!b.facilityId.equals(f.id) || !b.medicineId.equals(m.id)) continue;
                    if (b.expiryDate.isBefore(today)) {
                        expired += b.quantity;
                    } else {
                        usable += b.quantity;
                        count++;
                        if (nearest == null || b.expiryDate.isBefore(nearest)) nearest = b.expiryDate;
                    }
                }
                rows.add(new StockRow(f.name, m.name, usable, count, nearest, expired));
            }
        }
        return rows;
    }

    public StockBatch receiveBatch(NewBatch req) {
        if (req.quantity() <= 0 || req.expiryDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity and expiry date are required");
        }
        StockBatch b = new StockBatch();
        b.facilityId = req.facilityId();
        b.medicineId = req.medicineId();
        b.batchNo = req.batchNo() == null || req.batchNo().isBlank() ? "RCV-" + System.currentTimeMillis() : req.batchNo();
        b.quantity = req.quantity();
        b.expiryDate = req.expiryDate();
        return batchRepo.save(b);
    }

    /** Saves the day's consumption and takes the issued units out of stock, earliest expiry first. */
    @Transactional
    public Consumption recordConsumption(ConsumptionEntry req) {
        LocalDate date = req.date() == null ? today() : req.date();
        int issued = takeFromStock(req.facilityId(), req.medicineId(), req.issued(), date).stream().mapToInt(b -> b.quantity).sum();
        Consumption c = consumptionRepo.findByFacilityIdAndMedicineIdAndDate(req.facilityId(), req.medicineId(), date)
                .orElseGet(Consumption::new);
        c.facilityId = req.facilityId();
        c.medicineId = req.medicineId();
        c.date = date;
        c.issued += issued;
        c.demanded += req.demanded() == null ? req.issued() : req.demanded();
        return consumptionRepo.save(c);
    }

    public List<DayUsage> usageHistory(Long facilityId, Long medicineId, int days) {
        return consumptionRepo.findByFacilityIdAndMedicineIdAndDateGreaterThanEqualOrderByDateAsc(
                        facilityId, medicineId, today().minusDays(days))
                .stream().map(c -> new DayUsage(c.date, c.demanded, c.issued)).toList();
    }

    // ------------------------------------------------------------------ transfers

    public Transfer approveTransfer(TransferApproval req) {
        Transfer t = new Transfer();
        t.fromFacilityId = req.fromFacilityId();
        t.toFacilityId = req.toFacilityId();
        t.medicineId = req.medicineId();
        t.quantity = req.quantity();
        t.distanceKm = req.distanceKm();
        t.rationale = req.rationale();
        t.status = "APPROVED";
        t.createdAt = LocalDateTime.now();
        return transferRepo.save(t);
    }

    /** Moves the stock: removes batches from the sender (earliest expiry first) and adds them at the receiver. */
    @Transactional
    public Transfer completeTransfer(Long id) {
        Transfer t = transferRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        if (!"APPROVED".equals(t.status) && !"AUTO_APPROVED".equals(t.status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transfer is " + t.status);
        }
        List<StockBatch> moved = takeFromStock(t.fromFacilityId, t.medicineId, t.quantity, today());
        for (StockBatch part : moved) {
            part.facilityId = t.toFacilityId;   // same batch number and expiry, new facility
            batchRepo.save(part);
        }
        t.quantity = moved.stream().mapToInt(b -> b.quantity).sum();
        t.status = "COMPLETED";
        return transferRepo.save(t);
    }

    public Transfer rejectTransfer(Long id) {
        Transfer t = transferRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found"));
        t.status = "REJECTED";
        return transferRepo.save(t);
    }

    public List<TransferView> transfers() {
        Map<Long, String> fac = new HashMap<>();
        facilityRepo.findAll().forEach(f -> fac.put(f.id, f.name));
        Map<Long, String> med = new HashMap<>();
        medicineRepo.findAll().forEach(m -> med.put(m.id, m.name));
        return transferRepo.findAllByOrderByIdDesc().stream().map(t -> new TransferView(t.id, fac.get(t.fromFacilityId),
                fac.get(t.toFacilityId), med.get(t.medicineId), t.quantity, t.distanceKm, t.status, t.rationale,
                t.createdAt == null ? "" : t.createdAt.toString().replace('T', ' ').substring(0, 16))).toList();
    }

    // ------------------------------------------------------------------ purchase orders (procurement)

    public PurchaseOrder createPurchaseOrder(CreatePurchaseOrder req) {
        if (req.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order quantity must be positive");
        }
        Medicine m = medicineRepo.findById(req.medicineId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Medicine not found"));

        int leadTime = req.leadTimeDays() != null ? req.leadTimeDays() : m.leadTimeDays;
        double unitPrice = req.unitPrice() != null ? req.unitPrice() : m.unitPrice;
        double cost = req.totalCost() != null ? req.totalCost() : req.quantity() * unitPrice;
        LocalDate expectedDate = req.expectedDeliveryDate() != null ? req.expectedDeliveryDate() : today().plusDays(leadTime);

        PurchaseOrder po = new PurchaseOrder();
        po.facilityId = req.facilityId();
        po.medicineId = req.medicineId();
        po.quantity = req.quantity();
        po.leadTimeDays = leadTime;
        po.expectedDeliveryDate = expectedDate;
        po.unitPrice = unitPrice;
        po.totalCost = Math.round(cost * 100.0) / 100.0;
        po.urgency = req.urgency() != null ? req.urgency() : "ROUTINE";
        po.status = "ORDERED";
        po.createdAt = LocalDateTime.now();
        return purchaseOrderRepo.save(po);
    }

    public List<PurchaseOrderView> purchaseOrders() {
        Map<Long, String> fac = new HashMap<>();
        facilityRepo.findAll().forEach(f -> fac.put(f.id, f.name));
        Map<Long, String> med = new HashMap<>();
        medicineRepo.findAll().forEach(m -> med.put(m.id, m.name));

        return purchaseOrderRepo.findAllByOrderByIdDesc().stream().map(po -> new PurchaseOrderView(
                po.id, po.facilityId, po.medicineId,
                fac.getOrDefault(po.facilityId, "Unknown Facility"),
                med.getOrDefault(po.medicineId, "Unknown Medicine"),
                po.quantity, po.leadTimeDays, po.expectedDeliveryDate, po.totalCost,
                po.urgency, po.status,
                po.createdAt == null ? "" : po.createdAt.toString().replace('T', ' ').substring(0, 16),
                po.receivedAt == null ? "" : po.receivedAt.toString().replace('T', ' ').substring(0, 16)
        )).toList();
    }

    @Transactional
    public PurchaseOrder receivePurchaseOrder(Long id, ReceivePurchaseOrder req) {
        PurchaseOrder po = purchaseOrderRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found"));
        if (!"ORDERED".equals(po.status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Purchase order is already " + po.status);
        }

        LocalDate expiry = (req != null && req.expiryDate() != null) ? req.expiryDate() : today().plusMonths(24);
        String batchNo = (req != null && req.batchNo() != null && !req.batchNo().isBlank())
                ? req.batchNo() : "PO-" + po.id + "-" + System.currentTimeMillis() % 10000;

        StockBatch b = new StockBatch();
        b.facilityId = po.facilityId;
        b.medicineId = po.medicineId;
        b.batchNo = batchNo;
        b.quantity = po.quantity;
        b.expiryDate = expiry;
        batchRepo.save(b);

        po.status = "RECEIVED";
        po.receivedAt = LocalDateTime.now();
        return purchaseOrderRepo.save(po);
    }

    public PurchaseOrder cancelPurchaseOrder(Long id) {
        PurchaseOrder po = purchaseOrderRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found"));
        po.status = "CANCELLED";
        return purchaseOrderRepo.save(po);
    }

    // ------------------------------------------------------------------ batch details & disease trends

    public List<BatchDetailRow> detailedBatches(String district, Long facilityId, Long medicineId) {
        LocalDate today = today();
        Map<Long, String> facNames = new HashMap<>();
        Map<Long, String> facDistricts = new HashMap<>();
        facilityRepo.findAll().forEach(f -> {
            facNames.put(f.id, f.name);
            facDistricts.put(f.id, f.district);
        });
        Map<Long, String> medNames = new HashMap<>();
        medicineRepo.findAll().forEach(m -> medNames.put(m.id, m.name));

        return batchRepo.findAll().stream()
                .filter(b -> b.quantity > 0)
                .filter(b -> district == null || district.isBlank() || district.equalsIgnoreCase(facDistricts.get(b.facilityId)))
                .filter(b -> facilityId == null || facilityId.equals(b.facilityId))
                .filter(b -> medicineId == null || medicineId.equals(b.medicineId))
                .sorted(Comparator.comparing(b -> b.expiryDate))
                .map(b -> new BatchDetailRow(
                        b.id, b.facilityId, b.medicineId,
                        facNames.getOrDefault(b.facilityId, "Facility #" + b.facilityId),
                        medNames.getOrDefault(b.medicineId, "Medicine #" + b.medicineId),
                        b.batchNo, b.quantity, b.expiryDate,
                        java.time.temporal.ChronoUnit.DAYS.between(today, b.expiryDate),
                        b.expiryDate.isBefore(today)
                )).toList();
    }

    public Map<String, Object> diseaseTrends(String district) {
        List<DiseaseCase> allCases = diseaseCaseRepo.findByDistrictIgnoreCaseOrderByWeekStartAsc(district);
        Map<String, List<Map<String, Object>>> byDisease = new TreeMap<>();
        for (DiseaseCase c : allCases) {
            byDisease.computeIfAbsent(c.disease, k -> new ArrayList<>())
                    .add(Map.of("weekStart", c.weekStart.toString(), "cases", c.cases));
        }
        return Map.of("district", district, "diseases", byDisease);
    }

    /**
     * Takes qty units out of a facility's non-expired batches, earliest expiry first.
     * Returns the pieces taken (new, unsaved StockBatch objects).
     */
    private List<StockBatch> takeFromStock(Long facilityId, Long medicineId, int qty, LocalDate onDate) {
        List<StockBatch> taken = new ArrayList<>();
        for (StockBatch b : batchRepo.findByFacilityIdAndMedicineIdAndQuantityGreaterThanOrderByExpiryDateAsc(facilityId, medicineId, 0)) {
            if (qty <= 0) break;
            if (b.expiryDate.isBefore(onDate)) continue;   // expired: can't be used
            int take = Math.min(qty, b.quantity);
            b.quantity -= take;
            batchRepo.save(b);
            StockBatch piece = new StockBatch();
            piece.facilityId = facilityId;
            piece.medicineId = medicineId;
            piece.batchNo = b.batchNo;
            piece.quantity = take;
            piece.expiryDate = b.expiryDate;
            taken.add(piece);
            qty -= take;
        }
        return taken;
    }

    // ------------------------------------------------------------------ dashboard (history)

    /** Stock-out rate, fill rate, wastage and transfer outcomes for a district over the last `days` days. */
    public Map<String, Object> history(String district, int days) {
        List<Facility> facilities = facilityRepo.findByDistrictIgnoreCaseOrderByName(district);
        List<Long> ids = facilities.stream().map(f -> f.id).toList();
        LocalDate today = today();
        Map<Long, Medicine> meds = new HashMap<>();
        medicineRepo.findAll().forEach(m -> meds.put(m.id, m));

        long demanded = 0, issued = 0, dayRows = 0, stockoutRows = 0;
        Map<String, long[]> perMedicine = new TreeMap<>();   // name -> {demanded, issued, rows, stock-out rows}
        for (Consumption c : consumptionRepo.findByFacilityIdInAndDateGreaterThanEqual(ids, today.minusDays(days))) {
            if (c.demanded <= 0) continue;
            boolean stockout = c.issued < c.demanded;
            demanded += c.demanded;
            issued += c.issued;
            dayRows++;
            if (stockout) stockoutRows++;
            long[] a = perMedicine.computeIfAbsent(meds.get(c.medicineId).name, k -> new long[4]);
            a[0] += c.demanded;
            a[1] += c.issued;
            a[2]++;
            if (stockout) a[3]++;
        }

        // wastage = units in batches that are past their expiry date
        long wastedUnits = 0;
        double wastedValue = 0;
        for (StockBatch b : batchRepo.findByFacilityIdInAndQuantityGreaterThan(ids, 0)) {
            if (b.expiryDate.isBefore(today)) {
                wastedUnits += b.quantity;
                wastedValue += b.quantity * meds.get(b.medicineId).unitPrice;
            }
        }

        List<Transfer> transfers = transferRepo.findAll().stream().filter(t -> ids.contains(t.toFacilityId)).toList();
        List<Transfer> done = transfers.stream().filter(t -> "COMPLETED".equals(t.status)).toList();

        List<Map<String, Object>> byMedicine = new ArrayList<>();
        perMedicine.forEach((name, a) -> byMedicine.add(Map.of(
                "medicine", name,
                "fillRatePct", pct(a[1], a[0]),
                "stockOutRatePct", pct(a[3], a[2]))));

        List<PurchaseOrder> pos = purchaseOrderRepo.findAll().stream().filter(p -> ids.contains(p.facilityId)).toList();
        long posReceived = pos.stream().filter(p -> "RECEIVED".equals(p.status)).count();
        double poValue = pos.stream().mapToDouble(p -> p.totalCost).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("stockOutRatePct", pct(stockoutRows, dayRows));
        out.put("fillRatePct", pct(issued, demanded));
        out.put("wastedUnits", wastedUnits);
        out.put("wastedValueInr", Math.round(wastedValue));
        out.put("transfersApproved", transfers.size());
        out.put("transfersCompleted", done.size());
        out.put("unitsTransferred", done.stream().mapToInt(t -> t.quantity).sum());
        out.put("ordersPlaced", pos.size());
        out.put("ordersReceived", posReceived);
        out.put("ordersTotalCostInr", Math.round(poValue));
        out.put("byMedicine", byMedicine);
        return out;
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 10.0;
    }
}
