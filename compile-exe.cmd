@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem Builds the six tools into one folder.
rem Each .exe has its JAR inside. They share dist\java, one Java for all of them.
rem Console tools keep a console so progress, --help, and errors stay visible.
rem Backup and dbtran are window programs, so opening the window does not create a console.
rem Arguments still print in the console that launched them.
rem The servers and the studio are windows: their own screen is the interface,
rem and the server can hide in the notification area.

set "VERSION=1.0"
set "DEST=%~dp0dist"
set "STAGE=%DEST%\.staging"

call :resolve_jdk
if errorlevel 1 exit /b 1
call :resolve_mvn
if errorlevel 1 exit /b 1
call :check_jdk
if errorlevel 1 exit /b 1
call :resolve_csc
if errorlevel 1 exit /b 1

echo.
echo Building the JARs...
call "%MVN%" -f "%~dp0pom.xml" package
if errorlevel 1 (
    echo Maven package failed.
    exit /b 1
)

if exist "%DEST%" rmdir /s /q "%DEST%"
if exist "%DEST%" (
    echo Could not clear "%DEST%". Close anything using that folder and run again.
    exit /b 1
)
mkdir "%DEST%"
mkdir "%STAGE%"

call :runtime
if errorlevel 1 goto :failed
call :icon
if errorlevel 1 goto :failed
call :embed nexora-cli console
if errorlevel 1 goto :failed
call :embed nexora-backup hybrid
if errorlevel 1 goto :failed
call :embed nexora-dbtran hybrid
if errorlevel 1 goto :failed
call :embed nexora-dbsrv window
if errorlevel 1 goto :failed
call :embed nexora-dbeng window
if errorlevel 1 goto :failed
call :embed nexora-studio window
if errorlevel 1 goto :failed

call :cleanup
echo.
echo Done. The executables share one Java folder:
echo   %DEST%\nexora-cli.exe
echo   %DEST%\nexora-backup.exe
echo   %DEST%\nexora-dbtran.exe
echo   %DEST%\nexora-dbsrv.exe
echo   %DEST%\nexora-dbeng.exe
echo   %DEST%\nexora-studio.exe
echo   %DEST%\java
echo.
echo The JAR is inside each .exe, not in the folder.
echo Keep the executables next to java. Moving one .exe by itself leaves it without Java.
echo nexora-cli opens a console.
echo nexora-backup and nexora-dbtran do not open a console with the window.
exit /b 0

:failed
call :cleanup
echo.
echo Build failed.
exit /b 1

:resolve_jdk
set "JLINK="
where jlink >nul 2>&1
if not errorlevel 1 (
    for /f "delims=" %%P in ('where jlink') do (
        if not defined JLINK set "JLINK=%%P"
    )
)
if not defined JLINK if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jlink.exe" set "JLINK=%JAVA_HOME%\bin\jlink.exe"
if defined JLINK exit /b 0

set "JAVA_HOME_DETECTED="
for /f "tokens=1,* delims==" %%A in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do (
    if not defined JAVA_HOME_DETECTED set "JAVA_HOME_DETECTED=%%B"
)
if defined JAVA_HOME_DETECTED for /f "tokens=* delims= " %%H in ("%JAVA_HOME_DETECTED%") do set "JAVA_HOME_DETECTED=%%H"
if defined JAVA_HOME_DETECTED if exist "%JAVA_HOME_DETECTED%\bin\jlink.exe" set "JLINK=%JAVA_HOME_DETECTED%\bin\jlink.exe"
if defined JLINK exit /b 0

echo JDK 21 is required. jlink was not found.
echo Install a JDK 21 and add its bin directory to PATH, or set JAVA_HOME.
echo The java.exe shortcut on PATH is not enough: jlink ships only with the JDK.
exit /b 1

:resolve_mvn
set "MVN="
where mvn >nul 2>&1
if not errorlevel 1 (
    for /f "delims=" %%P in ('where mvn') do (
        if not defined MVN set "MVN=%%P"
    )
)
if not defined MVN if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" set "MVN=%M2_HOME%\bin\mvn.cmd"
if not defined MVN if exist "%USERPROFILE%\.maven\bin\mvn.cmd" set "MVN=%USERPROFILE%\.maven\bin\mvn.cmd"
if defined MVN exit /b 0

echo Maven was not found.
echo Install Maven and add it to PATH, or set MAVEN_HOME.
exit /b 1

:check_jdk
for %%I in ("%JLINK%") do set "JBIN=%%~dpI"
set "JAVA_MAJOR="
for /f "tokens=3" %%V in ('"%JBIN%java.exe" -version 2^>^&1') do (
    if not defined JAVA_MAJOR set "JAVA_MAJOR=%%~V"
)
for /f "delims=." %%M in ("%JAVA_MAJOR%") do set "JAVA_MAJOR=%%M"
if not defined JAVA_MAJOR (
    echo Could not read the Java version beside jlink.
    exit /b 1
)
if %JAVA_MAJOR% LSS 21 (
    echo jlink is from Java %JAVA_MAJOR%. Nexora needs JDK 21 or newer, so the bundled runtime can run the classes.
    exit /b 1
)
echo Using "%JLINK%"
echo Java %JAVA_MAJOR%
exit /b 0

:resolve_csc
set "CSC=%SystemRoot%\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if exist "%CSC%" exit /b 0
echo The .NET Framework compiler was not found. It is required to embed the JAR in the executable.
exit /b 1

:runtime
echo.
echo Linking the shared Java...
"%JBIN%jdeps.exe" --ignore-missing-deps --print-module-deps --multi-release 21 "%~dp0nexora-cli\target\nexora-cli-%VERSION%.jar" "%~dp0nexora-backup\target\nexora-backup-%VERSION%.jar" "%~dp0nexora-dbtran\target\nexora-dbtran-%VERSION%.jar" "%~dp0nexora-dbsrv\target\nexora-dbsrv-%VERSION%.jar" "%~dp0nexora-dbeng\target\nexora-dbeng-%VERSION%.jar" "%~dp0nexora-studio\target\nexora-studio-%VERSION%.jar" > "%STAGE%\modules.txt"
if errorlevel 1 (
    echo jdeps failed.
    exit /b 1
)
set "MODLIST="
set /p MODLIST=<"%STAGE%\modules.txt"
if not defined MODLIST (
    echo jdeps did not report any Java modules.
    exit /b 1
)
echo Modules: %MODLIST%
"%JBIN%jlink.exe" --add-modules %MODLIST% --strip-debug --no-header-files --no-man-pages --strip-native-commands --output "%DEST%\java"
if errorlevel 1 (
    echo jlink failed.
    exit /b 1
)
copy /y "%JBIN%java.exe" "%DEST%\java\bin\java.exe" >nul
if errorlevel 1 (
    echo Could not copy java.exe into the shared runtime.
    exit /b 1
)
copy /y "%JBIN%javaw.exe" "%DEST%\java\bin\javaw.exe" >nul
if errorlevel 1 (
    echo Could not copy javaw.exe into the shared runtime.
    exit /b 1
)
exit /b 0

:icon
echo.
echo Writing the icon...
set "ICON=%STAGE%\nexora.ico"
"%JBIN%javac.exe" -encoding UTF-8 -d "%STAGE%\icons" "%~dp0packaging\IconWriter.java"
if errorlevel 1 (
    echo Could not compile the icon writer.
    exit /b 1
)
"%JBIN%java.exe" -Djava.awt.headless=true -cp "%STAGE%\icons" IconWriter "%ICON%"
if errorlevel 1 (
    echo Could not write the icon.
    exit /b 1
)
exit /b 0

:embed
set "MODULE=%~1"
set "MODE=%~2"
set "APPJAR=%~dp0%MODULE%\target\%MODULE%-%VERSION%.jar"
set "OUT=%DEST%\%MODULE%.exe"
set "TARGET=exe"
set "DEFINE="
set "WINFORMS="
if /I "%MODE%"=="window" (
    set "TARGET=winexe"
    set "DEFINE=/define:WINDOW"
    set "WINFORMS=/reference:System.Windows.Forms.dll"
)
if /I "%MODE%"=="hybrid" (
    set "TARGET=winexe"
    set "DEFINE=/define:HYBRID"
    set "WINFORMS=/reference:System.Windows.Forms.dll"
)
if not exist "%APPJAR%" (
    echo Missing %MODULE%\target\%MODULE%-%VERSION%.jar. Run mvn package from the repository root.
    exit /b 1
)
echo Embedding %MODULE%...
"%CSC%" /nologo /optimize+ /target:%TARGET% %DEFINE% %WINFORMS% /win32icon:"%ICON%" /resource:"%APPJAR%",AppJar /out:"%OUT%" "%~dp0packaging\NexoraLauncher.cs"
if errorlevel 1 (
    echo Could not embed the JAR for %MODULE%.
    exit /b 1
)
exit /b 0

:cleanup
if exist "%STAGE%" rmdir /s /q "%STAGE%"
exit /b 0
