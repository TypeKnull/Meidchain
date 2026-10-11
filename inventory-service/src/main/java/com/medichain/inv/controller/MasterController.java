package com.medichain.inv.controller;

import com.medichain.inv.entity.Facility;
import com.medichain.inv.entity.Medicine;
import com.medichain.inv.service.MasterService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Master data: districts, facilities, essential medicine list. */
@RestController
@RequestMapping("/api")
public class MasterController {

    private final MasterService masterService;

    public MasterController(MasterService masterService) {
        this.masterService = masterService;
    }

    @GetMapping("/districts")
    public List<String> districts() {
        return masterService.districts();
    }

    @GetMapping("/facilities")
    public List<Facility> facilities(@RequestParam(required = false) String district) {
        return masterService.facilities(district);
    }

    @PostMapping("/facilities")
    public Facility addFacility(@RequestBody Facility facility) {
        return masterService.addFacility(facility);
    }

    @GetMapping("/medicines")
    public List<Medicine> medicines() {
        return masterService.medicines();
    }

    @PostMapping("/medicines")
    public Medicine addMedicine(@RequestBody Medicine medicine) {
        return masterService.addMedicine(medicine);
    }
}
