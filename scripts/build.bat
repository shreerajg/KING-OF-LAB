@echo off
cd %~dp0..
echo [King of Lab] Compiling Java files...

if not exist "out" mkdir "out"

:: Create a list of all .java files
dir /s /B src\*.java > sources.txt

:: Compile the project using Java 17+ with JavaFX modules and lib jars on classpath
javac -d "out" --module-path "lib" --add-modules javafx.controls,javafx.fxml,javafx.graphics -cp "lib\*" @sources.txt
del sources.txt

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [Error] Compilation failed! Please check the errors above.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [King of Lab] Compilation successful!
echo You can now use run.bat to start the application.
pause
