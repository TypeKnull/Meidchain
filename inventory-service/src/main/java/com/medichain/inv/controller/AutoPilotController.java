package com.medichain.inv.controller;

import com.medichain.inv.service.AutoPilotService;
import com.medichain.inv.service.AutoPilotService.AutoPilotAction;
import com.medichain.inv.service.AutoPilotService.AutoPilotSummary;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for Autonomous Supply Chain Auto-Pilot:
 * Allows operators to trigger automated rebalancing, inspect auto-actions audit logs,
 * and configure automated transfers/procurement.
 */
@RestController
@RequestMapping("/api/autopilot")
public class AutoPilotController {

    private final AutoPilotService autoPilotService;

    public AutoPilotController(AutoPilotService autoPilotService) {
        this.autoPilotService = autoPilotService;
    }

    /** Trigger autonomous stock protection & transfer execution. */
    @PostMapping("/run")
    public AutoPilotSummary run(@RequestParam(required = false) String district) {
        if (district == null || district.isBlank()) {
            List<AutoPilotSummary> list = autoPilotService.executeAll();
            return list.isEmpty() ? null : list.get(0);
        }
        return autoPilotService.execute(district);
    }

    /** Run across all districts in state. */
    @PostMapping("/run-all")
    public List<AutoPilotSummary> runAll() {
        return autoPilotService.executeAll();
    }

    /** View audit history of automated transfers and orders. */
    @GetMapping("/history")
    public List<AutoPilotAction> history() {
        return autoPilotService.getAuditHistory();
    }

    /** Check Auto-Pilot system status. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "enabled", autoPilotService.isEnabled(),
                "mode", "AUTONOMOUS_INTER_FACILITY_AND_PROCUREMENT_GUARD",
                "totalActionsRecorded", autoPilotService.getAuditHistory().size()
        );
    }

    /** Toggle Auto-Pilot on or off. */
    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestParam boolean enabled) {
        autoPilotService.setEnabled(enabled);
        return Map.of("enabled", autoPilotService.isEnabled());
    }
}
