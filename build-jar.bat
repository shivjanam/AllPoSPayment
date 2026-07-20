@echo off
echo ============================================
echo   ISO8583Studio - JAR Builder
echo   POS Expert Solutions Pvt Ltd
echo ============================================
echo.

cd /d "%~dp0"

echo Building Uber JAR...
echo This may take several minutes...
echo.

call gradlew.bat :composeApp:packageUberJarForCurrentOS --no-daemon

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ============================================
    echo   BUILD SUCCESSFUL!
    echo ============================================
    echo.
    echo JAR Location:
    echo   composeApp\build\compose\jars\
    echo.
    for %%F in (composeApp\build\compose\jars\*.jar) do (
        echo File: %%~nxF
        echo Size: %%~zF bytes
    )
    echo.
    echo To run the JAR:
    echo   java -jar composeApp\build\compose\jars\ISO8583Studio-windows-x64-1.0.14.jar
    echo.
    echo Opening output folder...
    start "" "composeApp\build\compose\jars"
) else (
    echo.
    echo ============================================
    echo   BUILD FAILED!
    echo ============================================
    echo Check the error messages above.
)

echo.
pause
