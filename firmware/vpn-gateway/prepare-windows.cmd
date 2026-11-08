@echo off
setlocal
cd /d "%~dp0"
echo =====================================================
echo  Smart Energy VPN Gateway - 2nd ESP32 - Setup Windows
echo =====================================================
echo  Uniquement pour le DEUXIEME ESP32, jamais le lecteur JK.
echo.
where git >nul 2>nul
if errorlevel 1 (
  echo [ERREUR] Installe Git for Windows puis relance ce script.
  exit /b 1
)
if not exist "upstream-tailtiny\.git" (
  git clone https://github.com/Asimmimx/tailtiny.git upstream-tailtiny
  if errorlevel 1 exit /b 1
)
git -C upstream-tailtiny checkout --detach d1ec2ae45e252e8dc737452be0f6afe1ac2e3edb
if errorlevel 1 exit /b 1
if not exist components mkdir components
xcopy /E /I /Y "upstream-tailtiny\components\tailtiny" "components\tailtiny" >nul
if errorlevel 2 (
  echo [ERREUR] Impossible de copier le composant TailTiny.
  exit /b 1
)
if not exist "sdkconfig.credentials" (
  copy /Y "sdkconfig.credentials.example" "sdkconfig.credentials" >nul
  echo Le modele de configuration locale a ete cree.
) else (
  echo Configuration locale deja presente - conservee sans modification.
)
echo.
echo [OK] Code TailTiny v3.1.0 installe localement.
echo Configure les secrets dans sdkconfig.credentials.
echo NE JAMAIS envoyer ce fichier ou les cles Tailscale sur GitHub.
echo.
echo Dans un terminal ESP-IDF 5.3.2, lance :
echo   cd /d "%CD%"
echo   idf.py set-target esp32
echo   idf.py -p COM5 flash monitor
echo.
echo Remplace COM5 par le port USB du 2e ESP32.
echo ATTENTION : impossible de tester le VPN sans des identifiants PERSONNELS.
echo.
start "" notepad "sdkconfig.credentials"
endlocal
