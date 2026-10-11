@echo off
echo ========================================================
echo Starting MediChain Services (PostgreSQL + Thymeleaf SSR)
echo ========================================================

echo Starting Forecast Service on port 8082...
start "MediChain - Forecast Service (Port 8082)" cmd /k "cd /d "%~dp0forecast-service" && java -jar target\forecast-service-1.0.0.jar"

timeout /t 3 /nobreak >nul

echo Starting Inventory Service on port 8081 (Thymeleaf SSR + Map)...
start "MediChain - Inventory Service (Port 8081)" cmd /k "cd /d "%~dp0inventory-service" && java -jar target\inventory-service-1.0.0.jar"

echo ========================================================
echo Services launched!
echo Access the Application at: http://localhost:8081/
echo Facility Map directly at:  http://localhost:8081/?tab=map
echo Facilities Master (SSR):   http://localhost:8081/facilities
echo Medicines Master (SSR):    http://localhost:8081/medicines
echo Transfers Master (SSR):    http://localhost:8081/transfers-view
echo ========================================================
