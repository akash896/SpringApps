package com.example.inventory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {
    private final InventoryService inventoryService;
    public InventoryController(InventoryService inventoryService) { this.inventoryService = inventoryService; }
    @GetMapping("/api/inventory/{sku}") public InventoryResponse availability(@PathVariable String sku) { return inventoryService.availability(sku); }
}
