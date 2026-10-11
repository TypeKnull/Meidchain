package com.medichain.inv.controller;

import com.medichain.inv.dto.StockDtos.*;
import com.medichain.inv.entity.Consumption;
import com.medichain.inv.entity.StockBatch;
import com.medichain.inv.entity.Transfer;
import com.medichain.inv.service.StockService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Stock, batches, daily consumption and transfer orders. */
@RestController
@RequestMapping("/api")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/stock")
    public List<StockRow> stock(@RequestParam String district) {
        return stockService.stockTable(district);
    }

    @GetMapping("/stock/batches")
    public List<BatchDetailRow> batches(@RequestParam(required = false) String district,
                                        @RequestParam(required = false) Long facilityId,
                                        @RequestParam(required = false) Long medicineId) {
        return stockService.detailedBatches(district, facilityId, medicineId);
    }

    @PostMapping("/stock/batches")
    public StockBatch receiveBatch(@RequestBody NewBatch request) {
        return stockService.receiveBatch(request);
    }

    @PostMapping("/consumption")
    public Consumption recordConsumption(@RequestBody ConsumptionEntry request) {
        return stockService.recordConsumption(request);
    }

    @GetMapping("/consumption")
    public List<DayUsage> usage(@RequestParam Long facilityId, @RequestParam Long medicineId,
                                @RequestParam(defaultValue = "60") int days) {
        return stockService.usageHistory(facilityId, medicineId, days);
    }

    @GetMapping("/transfers")
    public List<TransferView> transfers() {
        return stockService.transfers();
    }

    /** Approve a transfer recommended by the AI. */
    @PostMapping("/transfers")
    public Transfer approve(@RequestBody TransferApproval request) {
        return stockService.approveTransfer(request);
    }

    @PostMapping("/transfers/{id}/complete")
    public Transfer complete(@PathVariable Long id) {
        return stockService.completeTransfer(id);
    }

    @PostMapping("/transfers/{id}/reject")
    public Transfer reject(@PathVariable Long id) {
        return stockService.rejectTransfer(id);
    }

    // ------------------------------------------------------------------ purchase orders (procurement)

    @GetMapping("/purchase-orders")
    public List<PurchaseOrderView> purchaseOrders() {
        return stockService.purchaseOrders();
    }

    @PostMapping("/purchase-orders")
    public com.medichain.inv.entity.PurchaseOrder createPurchaseOrder(@RequestBody CreatePurchaseOrder request) {
        return stockService.createPurchaseOrder(request);
    }

    @PostMapping("/purchase-orders/{id}/receive")
    public com.medichain.inv.entity.PurchaseOrder receivePurchaseOrder(@PathVariable Long id,
                                                                      @RequestBody(required = false) ReceivePurchaseOrder request) {
        return stockService.receivePurchaseOrder(id, request);
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    public com.medichain.inv.entity.PurchaseOrder cancelPurchaseOrder(@PathVariable Long id) {
        return stockService.cancelPurchaseOrder(id);
    }
}
