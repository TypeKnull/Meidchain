package com.medichain.inv.controller;

import com.medichain.inv.dto.StockDtos.StockRow;
import com.medichain.inv.dto.StockDtos.TransferView;
import com.medichain.inv.entity.Facility;
import com.medichain.inv.entity.Medicine;
import com.medichain.inv.service.MasterService;
import com.medichain.inv.service.StockService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Spring MVC Web View Controller for Server-Side Thymeleaf Template Rendering.
 * Binds data from PostgreSQL directly to Thymeleaf HTML templates.
 */
@Controller
public class WebViewController {

    private final MasterService masterService;
    private final StockService stockService;

    public WebViewController(MasterService masterService, StockService stockService) {
        this.masterService = masterService;
        this.stockService = stockService;
    }

    /**
     * Main dashboard view rendered server-side with Thymeleaf.
     */
    @GetMapping({"/", "/dashboard"})
    public String index(Model model, 
                         @RequestParam(value = "district", required = false) String district,
                         @RequestParam(value = "tab", required = false) String tab) {
        List<String> districts = masterService.districts();
        String selectedDistrict = (district != null && !district.isBlank())
                ? district
                : (districts.isEmpty() ? "Vellore" : districts.get(0));

        List<Facility> facilities = masterService.facilities(selectedDistrict);
        List<Medicine> medicines = masterService.medicines();
        List<TransferView> transfers = stockService.transfers();
        List<StockRow> stockRows = stockService.stockTable(selectedDistrict);

        model.addAttribute("title", "MediChain - Smart Healthcare Supply Prototype (Thymeleaf SSR)");
        model.addAttribute("districts", districts);
        model.addAttribute("selectedDistrict", selectedDistrict);
        model.addAttribute("systemStatus", "Live System: AI :8082 | PostgreSQL DB :5432");
        model.addAttribute("activeTab", (tab != null && !tab.isBlank()) ? tab : "dashboard");

        // Server-Side Thymeleaf template attributes
        model.addAttribute("facilities", facilities);
        model.addAttribute("medicines", medicines);
        model.addAttribute("transfers", transfers);
        model.addAttribute("stockRows", stockRows);

        // Summary counts
        model.addAttribute("totalFacilities", facilities.size());
        model.addAttribute("totalMedicines", medicines.size());
        model.addAttribute("totalTransfers", transfers.size());
        model.addAttribute("recentTransfers", transfers.stream().limit(6).toList());

        return "index";
    }

    /**
     * Dedicated Thymeleaf view for Healthcare Facilities Master.
     */
    @GetMapping("/facilities")
    public String facilitiesView(Model model, @RequestParam(value = "district", required = false) String district) {
        List<String> districts = masterService.districts();
        String selectedDistrict = (district != null && !district.isBlank())
                ? district
                : (districts.isEmpty() ? "All" : districts.get(0));

        List<Facility> facilities = (district == null || district.equalsIgnoreCase("All"))
                ? masterService.facilities(null)
                : masterService.facilities(selectedDistrict);

        model.addAttribute("title", "MediChain - Healthcare Facilities Master (Thymeleaf View)");
        model.addAttribute("districts", districts);
        model.addAttribute("selectedDistrict", selectedDistrict);
        model.addAttribute("facilities", facilities);
        model.addAttribute("totalFacilities", facilities.size());
        model.addAttribute("activeTab", "facilities");
        model.addAttribute("systemStatus", "Live System: AI :8082 | PostgreSQL DB :5432");

        return "facilities";
    }

    /**
     * Dedicated Thymeleaf view for Essential Medicines Catalog.
     */
    @GetMapping("/medicines")
    public String medicinesView(Model model) {
        List<Medicine> medicines = masterService.medicines();
        List<String> districts = masterService.districts();

        model.addAttribute("title", "MediChain - Essential Medicines Catalog (Thymeleaf View)");
        model.addAttribute("districts", districts);
        model.addAttribute("medicines", medicines);
        model.addAttribute("totalMedicines", medicines.size());
        model.addAttribute("activeTab", "medicines");
        model.addAttribute("systemStatus", "Live System: AI :8082 | PostgreSQL DB :5432");

        return "medicines";
    }

    /**
     * Dedicated Thymeleaf view for Inter-Facility Stock Transfers.
     */
    @GetMapping("/transfers-view")
    public String transfersView(Model model) {
        List<TransferView> transfers = stockService.transfers();
        List<String> districts = masterService.districts();

        model.addAttribute("title", "MediChain - Inter-Facility Transfers (Thymeleaf View)");
        model.addAttribute("districts", districts);
        model.addAttribute("transfers", transfers);
        model.addAttribute("totalTransfers", transfers.size());
        model.addAttribute("activeTab", "transfers-ssr");
        model.addAttribute("systemStatus", "Live System: AI :8082 | PostgreSQL DB :5432");

        return "transfers";
    }
}
