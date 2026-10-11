package com.medichain.inv.controller;

import com.medichain.inv.dto.ForecastResponse;
import com.medichain.inv.dto.ForecastResponse.RiskRow;
import com.medichain.inv.dto.ForecastResponse.TransferRow;
import com.medichain.inv.entity.Facility;
import com.medichain.inv.repository.FacilityRepository;
import com.medichain.inv.service.QueryService;
import com.medichain.inv.service.RoutePlannerService;
import com.medichain.inv.service.RoutePlannerService.DeliveryRoutePlan;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class RouteController {

    private final FacilityRepository facilityRepo;
    private final QueryService queryService;
    private final RoutePlannerService routePlannerService;

    public RouteController(FacilityRepository facilityRepo, QueryService queryService,
                           RoutePlannerService routePlannerService) {
        this.facilityRepo = facilityRepo;
        this.queryService = queryService;
        this.routePlannerService = routePlannerService;
    }

    public record MapFacilityItem(
            Long id,
            String code,
            String name,
            String type,
            String district,
            double latitude,
            double longitude,
            int population,
            int totalUsableStock,
            String riskLevel,
            List<String> criticalMedicines,
            List<String> surplusMedicines
    ) {}

    public record MapTransferVector(
            Long fromFacilityId,
            String fromFacility,
            double fromLat,
            double fromLon,
            Long toFacilityId,
            String toFacility,
            double toLat,
            double toLon,
            String medicineName,
            int quantity,
            String unit,
            double distanceKm,
            String urgency,
            String rationale
    ) {}

    @GetMapping("/map/facility-network")
    public Map<String, Object> getFacilityNetwork(@RequestParam String district) {
        List<Facility> facilities = facilityRepo.findByDistrictIgnoreCaseOrderByName(district);
        Map<Long, Facility> facMap = new HashMap<>();
        facilities.forEach(f -> facMap.put(f.id, f));

        ForecastResponse forecast = queryService.analyse(district, List.of(), 14);

        // Facility risk mapping & stock summary
        Map<Long, List<RiskRow>> risksByFac = new HashMap<>();
        for (RiskRow r : forecast.risks()) {
            risksByFac.computeIfAbsent(r.facilityId(), k -> new ArrayList<>()).add(r);
        }

        List<MapFacilityItem> facItems = new ArrayList<>();
        double sumLat = 0, sumLon = 0;

        for (Facility f : facilities) {
            sumLat += f.latitude;
            sumLon += f.longitude;
            List<RiskRow> rList = risksByFac.getOrDefault(f.id, List.of());

            String overallRisk = "LOW";
            boolean hasCrit = rList.stream().anyMatch(r -> "CRITICAL".equals(r.riskLevel()));
            boolean hasHigh = rList.stream().anyMatch(r -> "HIGH".equals(r.riskLevel()));
            boolean hasMed = rList.stream().anyMatch(r -> "MEDIUM".equals(r.riskLevel()));

            if (hasCrit) overallRisk = "CRITICAL";
            else if (hasHigh) overallRisk = "HIGH";
            else if (hasMed) overallRisk = "MEDIUM";

            int totalStock = rList.stream().mapToInt(RiskRow::usableStock).sum();

            List<String> critMeds = rList.stream()
                    .filter(RiskRow::runsOutInHorizon)
                    .map(r -> String.format("%s (%dd left, %s)", r.medicineName(), r.daysToStockout(), r.stockoutDate()))
                    .toList();

            List<String> surplusMeds = forecast.transfers().stream()
                    .filter(t -> t.fromFacilityId().equals(f.id))
                    .map(t -> String.format("%s (+%,d %ss)", t.medicineName(), t.quantity(), t.unit()))
                    .distinct()
                    .toList();

            facItems.add(new MapFacilityItem(f.id, f.code, f.name, f.type, f.district,
                    f.latitude, f.longitude, f.population, totalStock, overallRisk, critMeds, surplusMeds));
        }

        // Transfer arrows / vectors
        List<MapTransferVector> vectors = new ArrayList<>();
        for (TransferRow t : forecast.transfers()) {
            Facility from = facMap.get(t.fromFacilityId());
            Facility to = facMap.get(t.toFacilityId());
            if (from != null && to != null) {
                vectors.add(new MapTransferVector(
                        t.fromFacilityId(), t.fromFacility(), from.latitude, from.longitude,
                        t.toFacilityId(), t.toFacility(), to.latitude, to.longitude,
                        t.medicineName(), t.quantity(), t.unit(), t.distanceKm(), t.urgency(), t.rationale()
                ));
            }
        }

        double centerLat = facilities.isEmpty() ? 12.9165 : sumLat / facilities.size();
        double centerLon = facilities.isEmpty() ? 79.1325 : sumLon / facilities.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("district", district);
        result.put("centerLat", centerLat);
        result.put("centerLon", centerLon);
        result.put("facilities", facItems);
        result.put("transfers", vectors);
        result.put("transfersCount", vectors.size());

        return result;
    }

    @PostMapping("/routes/plan")
    public DeliveryRoutePlan planRoute(
            @RequestParam(defaultValue = "Vellore") String district,
            @RequestParam(defaultValue = "5000") int vanCapacity) {
        return routePlannerService.planRoute(district, vanCapacity);
    }
}
