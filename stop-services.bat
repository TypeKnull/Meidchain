@echo off
echo Stopping MediChain services on port 8081 and 8082...

for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8081 ^| findstr LISTENING') do (
    echo Terminating process %%a on port 8081...
    taskkill /F /PID %%a
)

for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8082 ^| findstr LISTENING') do (
    echo Terminating process %%a on port 8082...
    taskkill /F /PID %%a
)

echo MediChain services stopped cleanly.
