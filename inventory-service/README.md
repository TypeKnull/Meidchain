# MediChain – Inventory Service (data + web UI)

Standalone Spring Boot microservice on **port 8081**. It connects to **MySQL 8.0** (port 3306) and hosts the web page.
For predictions it calls the **Forecast Service on port 8082** over REST (`POST /api/forecast`).

## Run
1. Start the forecast service first (`mvn spring-boot:run` in the forecast-service folder).
2. Here: `mvn spring-boot:run` — demo data seeds automatically into MySQL on the first start.
3. Open **http://localhost:8081** and click **Analyse**.

* **Database**: MySQL 8.0 (`jdbc:mysql://localhost:3306/medichain`, user `root`, password `root`)
* **Reset demo data**: `DROP DATABASE medichain; CREATE DATABASE medichain;` in MySQL, or restart the service
* **Optional H2 profile**: To run with embedded H2 instead of MySQL, run with `--spring.profiles.active=h2`
* **Forecast service address**: configured via `forecast.base-url` in `application.properties`

## Screens
1. **Ask & Analyse** – question → summary, stock-out risk table (with "Why?"), transfers (Approve), orders, expiry risk
2. **Dashboard** – stock-out rate, fill rate, expired stock, transfer outcomes, current risk
3. **Stock & Master Data** – stock table, receive a batch, record consumption, medicine list, facilities
4. **Transfer Orders** – approved transfers; Complete moves the stock in the database

## API
| Method | Path | Purpose |
|---|---|---|
| GET | /api/districts | districts |
| GET/POST | /api/facilities, /api/medicines | master data |
| GET | /api/stock?district= | stock per facility × medicine |
| GET | /api/stock/batches?district= | granular batch-level inventory and expiry ledger |
| POST | /api/stock/batches | receive a batch |
| GET/POST | /api/consumption | usage history / record a day's consumption (stock reduced FEFO) |
| GET/POST | /api/transfers, /api/transfers/{id}/complete, /reject | transfer orders |
| GET/POST | /api/purchase-orders, /api/purchase-orders/{id}/receive, /cancel | procurement lifecycle & batch check-in |
| POST | /api/query `{"question": "...", "district": "Vellore"}` | question → AI answer (calls forecast service) |
| GET | /api/dashboard?district= | dashboard numbers (calls forecast service) |
| GET | /api/disease-trends?district= | seasonal epidemiology surveillance caseloads |
| POST | /api/import/{medicines,facilities,disease-cases,stock-batches} | ingest datasets from external sources |
| POST/GET | /api/autopilot/{run,run-all,history,status,toggle} | autonomous auto-transfer & auto-procurement engine |

## Code
```
InvApplication.java
controller/  MasterController, StockController, AnalysisController, ImportController, AutoPilotController
service/     MasterService, StockService, QueryService, ForecastClient, AutoPilotService
repository/  7 Spring Data interfaces (including PurchaseOrderRepository)
entity/      Facility, Medicine, StockBatch, Consumption, DiseaseCase, Transfer, PurchaseOrder
dto/         ForecastRequest, ForecastResponse (same as in forecast-service), StockDtos
config/      RestClientConfig, DataSeeder, SimulatedData
resources/static/  index.html, app.js, style.css
```
