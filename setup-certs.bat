@echo off
echo.
echo === GCN Voice - Certificate Setup ===
echo.

where mkcert >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo ERROR: mkcert is not installed.
    echo Install it with: choco install mkcert  OR  scoop install mkcert
    echo See: https://github.com/FiloSottile/mkcert
    exit /b 1
)

echo [1/2] Installing local CA into system trust store...
mkcert -install

if not exist "server\certs" mkdir "server\certs"

echo [2/2] Generating certificates for localhost...
mkcert -key-file server\certs\key.pem -cert-file server\certs\cert.pem localhost 127.0.0.1 ::1

echo.
echo === Done! Certificates created in server\certs\ ===
echo.
echo You can now start the app:
echo   1. cd server ^&^& npm start
echo   2. cd client ^&^& npm run dev
echo.
