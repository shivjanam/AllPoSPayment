@echo off
echo ============================================
echo   ISO8583Studio - Windows EXE Builder
echo   POS Expert Solutions Pvt Ltd
echo ============================================
echo.

cd /d "%~dp0"

echo Building Windows Installer (EXE)...
echo This may take several minutes...
echo.

call gradlew.bat :composeApp:packageExe --no-daemon

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ============================================
    echo   BUILD SUCCESSFUL!
    echo ============================================
    echo.
    echo EXE Location:
    echo   composeApp\build\compose\binaries\main\exe\
    echo.
    for %%F in (composeApp\build\compose\binaries\main\exe\*.exe) do (
        echo File: %%~nxF
        echo Size: %%~zF bytes
    )
    echo.
    echo Opening output folder...
    start "" "composeApp\build\compose\binaries\main\exe"
) else (
    echo.
    echo ============================================
    echo   BUILD FAILED!
    echo ============================================
    echo Check the error messages above.
)

echo.
pause
