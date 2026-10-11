package com.medichain.inv.service;

import com.medichain.inv.dto.ForecastResponse;
import com.medichain.inv.dto.ForecastResponse.TransferRow;
import com.medichain.inv.entity.Facility;
import com.medichain.inv.repository.FacilityRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * One-Trip Vehicle Delivery Route Planner.
 * Combines separate inter-facility redistribution transfers into a single optimized vehicle route,
 * respecting the van's capacity and calculating total kilometers saved using nearest-neighbour routing.
 */
@Service
public class RoutePlannerService {

    public record RouteStop(
            int stopNumber,
            Long facilityId,
            String facilityName,
            String facilityType,
            double latitude,
            double longitude,
            String action,
            int pickupUnits,
            int dropoffUnits,
            int vanCargoAfterStop,
            double segmentDistanceKm,
            double cumulativeDistanceKm
    ) {}

    public record DeliveryRoutePlan(
            String district,
            int vanCapacity,
            int peakVanLoad,
            int totalTransfersFulfilled,
            int totalUnitsMoved,
            double combinedRouteKm,
            double separateTripsKm,
            double kmSaved,
            double pctSaved,
            double fuelSavedLitres,
            double co2SavedKg,
            List<RouteStop> stops,
            List<double[]> polylineCoordinates
    ) {}

    private final FacilityRepository facilityRepo;
    private final QueryService queryService;

    public RoutePlannerService(FacilityRepository facilityRepo, QueryService queryService) {
        this.facilityRepo = facilityRepo;
        this.queryService = queryService;
    }

    /** Road-approximated distance: haversine distance x 1.3 */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        double straightKm = 2 * 6371 * Math.asin(Math.sqrt(h));
        return Math.round(straightKm * 1.3 * 10.0) / 10.0;
    }

    public DeliveryRoutePlan planRoute(String district, int vanCapacity) {
        if (vanCapacity <= 0) vanCapacity = 5000;
        List<Facility> allFacs = facilityRepo.findByDistrictIgnoreCaseOrderByName(district);
        Map<Long, Facility> facMap = new HashMap<>();
        allFacs.forEach(f -> facMap.put(f.id, f));

        ForecastResponse forecast = queryService.analyse(district, List.of(), 14);
        List<TransferRow> transfers = forecast.transfers();

        if (transfers.isEmpty()) {
            return emptyPlan(district, vanCapacity, allFacs);
        }

        // Calculate separate trips distance (round trip for each individual transfer)
        double separateTripsKm = 0;
        for (TransferRow t : transfers) {
            Facility from = facMap.get(t.fromFacilityId());
            Facility to = facMap.get(t.toFacilityId());
            if (from != null && to != null) {
                separateTripsKm += 2 * distanceKm(from.latitude, from.longitude, to.latitude, to.longitude);
            }
        }
        separateTripsKm = Math.round(separateTripsKm * 10.0) / 10.0;

        // Choose starting depot: prefer District Hospital ("DH"), or first facility
        Facility depot = allFacs.stream()
                .filter(f -> "DH".equalsIgnoreCase(f.type))
                .findFirst()
                .orElse(allFacs.get(0));

        class ItemInTransit {
            final TransferRow transfer;
            ItemInTransit(TransferRow transfer) { this.transfer = transfer; }
        }

        List<TransferRow> pendingPickups = new ArrayList<>(transfers);
        List<ItemInTransit> cargoOnboard = new ArrayList<>();

        List<RouteStop> stops = new ArrayList<>();
        List<double[]> polyline = new ArrayList<>();

        Facility current = depot;
        double cumulativeKm = 0;
        int currentCargo = 0;
        int peakCargo = 0;
        int stopCount = 0;

        // Starting depot departure
        stops.add(new RouteStop(stopCount++, current.id, current.name, current.type,
                current.latitude, current.longitude, "START: Depart from Central Supply Depot",
                0, 0, 0, 0, 0));
        polyline.add(new double[]{current.latitude, current.longitude});

        // Nearest-Neighbour Greedy Routing loop
        while (!pendingPickups.isEmpty() || !cargoOnboard.isEmpty()) {
            Set<Long> candidateIds = new LinkedHashSet<>();
            for (ItemInTransit item : cargoOnboard) {
                candidateIds.add(item.transfer.toFacilityId());
            }
            for (TransferRow t : pendingPickups) {
                if (currentCargo + t.quantity() <= vanCapacity) {
                    candidateIds.add(t.fromFacilityId());
                }
            }

            // If van is full, force delivery of onboard items
            if (candidateIds.isEmpty() && !cargoOnboard.isEmpty()) {
                for (ItemInTransit item : cargoOnboard) {
                    candidateIds.add(item.transfer.toFacilityId());
                }
            }

            if (candidateIds.isEmpty()) break;

            // Nearest valid candidate
            Facility nextFacility = null;
            double shortestDist = Double.MAX_VALUE;
            for (Long fid : candidateIds) {
                Facility cand = facMap.get(fid);
                if (cand == null) continue;
                double d = distanceKm(current.latitude, current.longitude, cand.latitude, cand.longitude);
                if (d < shortestDist) {
                    shortestDist = d;
                    nextFacility = cand;
                }
            }

            if (nextFacility == null) break;

            cumulativeKm += shortestDist;
            cumulativeKm = Math.round(cumulativeKm * 10.0) / 10.0;
            current = nextFacility;

            // 1. Deliver items destined for nextFacility
            int dropoffQty = 0;
            List<String> actions = new ArrayList<>();
            Iterator<ItemInTransit> cargoIt = cargoOnboard.iterator();
            while (cargoIt.hasNext()) {
                ItemInTransit item = cargoIt.next();
                if (item.transfer.toFacilityId().equals(current.id)) {
                    dropoffQty += item.transfer.quantity();
                    currentCargo -= item.transfer.quantity();
                    actions.add(String.format("DELIVER: %,d %s %s", item.transfer.quantity(), item.transfer.unit(), item.transfer.medicineName()));
                    cargoIt.remove();
                }
            }

            // 2. Pick up items originating at nextFacility that fit in remaining capacity
            int pickupQty = 0;
            Iterator<TransferRow> pickupIt = pendingPickups.iterator();
            while (pickupIt.hasNext()) {
                TransferRow t = pickupIt.next();
                if (t.fromFacilityId().equals(current.id) && currentCargo + t.quantity() <= vanCapacity) {
                    pickupQty += t.quantity();
                    currentCargo += t.quantity();
                    cargoOnboard.add(new ItemInTransit(t));
                    Facility dest = facMap.get(t.toFacilityId());
                    actions.add(String.format("PICKUP: %,d %s %s (for %s)", t.quantity(), t.unit(), t.medicineName(), dest != null ? dest.name : "destination"));
                    pickupIt.remove();
                }
            }

            peakCargo = Math.max(peakCargo, currentCargo);
            String actionSummary = actions.isEmpty() ? "TRANSIT: Pass-through checkpoint" : String.join(" | ", actions);

            stops.add(new RouteStop(stopCount++, current.id, current.name, current.type,
                    current.latitude, current.longitude, actionSummary, pickupQty, dropoffQty,
                    currentCargo, shortestDist, cumulativeKm));
            polyline.add(new double[]{current.latitude, current.longitude});
        }

        // Return leg to depot
        if (!current.id.equals(depot.id)) {
            double returnDist = distanceKm(current.latitude, current.longitude, depot.latitude, depot.longitude);
            cumulativeKm += returnDist;
            cumulativeKm = Math.round(cumulativeKm * 10.0) / 10.0;
            stops.add(new RouteStop(stopCount++, depot.id, depot.name, depot.type,
                    depot.latitude, depot.longitude, "END: Return to Depot (Delivery Mission Complete)",
                    0, 0, 0, returnDist, cumulativeKm));
            polyline.add(new double[]{depot.latitude, depot.longitude});
        }

        double kmSaved = Math.max(0, Math.round((separateTripsKm - cumulativeKm) * 10.0) / 10.0);
        double pctSaved = separateTripsKm > 0 ? Math.round((kmSaved / separateTripsKm) * 1000.0) / 10.0 : 0.0;
        double fuelSaved = Math.round((kmSaved / 8.5) * 10.0) / 10.0;
        double co2Saved = Math.round((kmSaved * 0.22) * 10.0) / 10.0;

        int totalUnits = transfers.stream().mapToInt(TransferRow::quantity).sum();

        return new DeliveryRoutePlan(district, vanCapacity, peakCargo, transfers.size(), totalUnits,
                cumulativeKm, separateTripsKm, kmSaved, pctSaved, fuelSaved, co2Saved, stops, polyline);
    }

    private DeliveryRoutePlan emptyPlan(String district, int vanCapacity, List<Facility> facs) {
        Facility depot = facs.isEmpty() ? null : facs.get(0);
        List<RouteStop> stops = new ArrayList<>();
        List<double[]> poly = new ArrayList<>();
        if (depot != null) {
            stops.add(new RouteStop(0, depot.id, depot.name, depot.type, depot.latitude, depot.longitude,
                    "No transfers pending. All facilities are safely balanced.", 0, 0, 0, 0, 0));
            poly.add(new double[]{depot.latitude, depot.longitude});
        }
        return new DeliveryRoutePlan(district, vanCapacity, 0, 0, 0, 0, 0, 0, 0, 0, 0, stops, poly);
    }
}
