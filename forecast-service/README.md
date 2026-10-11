# MediChain – Forecast Service (AI)

Standalone Spring Boot microservice (port 8082). No database, no UI.
It receives stock + consumption history + disease cases in one request and returns:
demand forecast, days to stock-out, risk level, transfers, purchase orders and expiry risk, with explanations.

## Run
```bash
mvn spring-boot:run
```
Check: http://localhost:8082/api/forecast/health

## Try it (sample data included)
```bash
curl -X POST http://localhost:8082/api/forecast \
     -H "Content-Type: application/json" \
     -d @sample-request.json
```
`sample-request.json` = 8 facilities in Vellore, paracetamol + ORS, 120 days of history, next 10 days.
Expected first summary line: *PHC Gudiyatham will run out of Paracetamol in 5 day(s)... transfer 1,720 tablets from PHC Pallikonda (11 km away).*

## API
`POST /api/forecast` — body `ForecastRequest`, returns `ForecastResponse` (see `dto/`).

| Request field | Meaning |
|---|---|
| district, asOf, horizonDays | where, "today", how many days ahead |
| facilities | id, name, latitude, longitude |
| medicines | id, code, name, unit, diseaseTag (FEVER / DIARRHOEA / ARI / NONE), leadTimeDays, unitPrice |
| batches | facilityId, medicineId, batchNo, quantity, expiryDate |
| consumption | facilityId, medicineId, date, quantity (units demanded that day) |
| diseaseCases | disease, weekStart, cases |

Response: `summary`, `risks` (days to stock-out, chance, level, key factors), `transfers` (with rationale),
`orders`, `expiry`, `method`.

## Code
```
ForecastApplication.java
controller/ForecastController.java     POST /api/forecast, GET /api/forecast/health
service/ForecastService.java           runs the steps below + plain-English summary
service/StockOutRiskService.java       day-by-day stock simulation → days to stock-out, chance, risk, factors
service/RedistributionService.java     transfers: most urgent first, nearest safe surplus, expiring stock preferred
service/ProcurementService.java        reorder point = forecast × lead time + safety stock
service/ExpiryRiskService.java         batches that will expire before use
service/FacilityMedicine.java          working data for one medicine at one facility
ml/DemandPredictionModel.java          Holt time-series + disease-case regression (blend, weight 0.6 × R²)
ml/ModelEvaluator.java                 14-day backtest error
dto/ForecastRequest.java, ForecastResponse.java
config/ForecastConfig.java             max transfer km (75), buffer days (7), safety factor z (1.65)
```
