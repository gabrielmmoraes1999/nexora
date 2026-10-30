@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem Builds the six tools into one folder.
rem Each x64 .exe hosts the JVM in-process and has its JAR inside. They share dist\java, one Java for all of them.
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
call "%MVN%" -f "%~dp0pom.xml" clean package
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
call :embed seadb-cli console
if errorlevel 1 goto :failed
call :embed seadb-backup hybrid
if errorlevel 1 goto :failed
call :embed seadb-dbtran hybrid
if errorlevel 1 goto :failed
call :embed seadb-dbsrv window
if errorlevel 1 goto :failed
call :embed seadb-dbeng window
if errorlevel 1 goto :failed
call :embed seadb-studio window
if errorlevel 1 goto :failed

call :cleanup
echo.
echo Done. The executables share one Java folder:
echo   %DEST%\seadb-cli.exe
echo   %DEST%\seadb-backup.exe
echo   %DEST%\seadb-dbtran.exe
echo   %DEST%\seadb-dbsrv.exe
echo   %DEST%\seadb-dbeng.exe
echo   %DEST%\seadb-studio.exe
echo   %DEST%\java
echo.
echo The JAR is inside each .exe, not in the folder.
echo Keep the executables next to java. Moving one .exe by itself leaves it without Java.
echo seadb-cli opens a console.
echo seadb-backup and seadb-dbtran do not open a console with the window.
exit /b 0

:failed
call :cleanup
echo.
echo Build failed.
exit /b 1

:resolve_jdk
set "JLINK="
if defined JAVA_HOME call :try_jdk "%JAVA_HOME%"
if defined JLINK goto :jdk_ready

rem Search installed JDKs before using PATH; Oracle's java shortcut may hang.
for %%R in ("%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Amazon Corretto" "%USERPROFILE%\.jdks") do (
    if exist "%%~R" for /d %%D in ("%%~R\*") do call :try_jdk "%%~D"
)
if defined JLINK goto :jdk_ready
for /f "delims=" %%P in ('where jlink.exe 2^>nul') do for %%D in ("%%~dpP..") do call :try_jdk "%%~fD"
if defined JLINK goto :jdk_ready

echo JDK 21 or newer was not found.
echo Install a JDK 21 and add its bin directory to PATH, or set JAVA_HOME.
exit /b 1

:try_jdk
if defined JLINK exit /b 0
if not exist "%~1\bin\jlink.exe" exit /b 0
if not exist "%~1\bin\javac.exe" exit /b 0
if not exist "%~1\bin\java.exe" exit /b 0
if not exist "%~1\release" exit /b 0
set "CANDIDATE_VERSION="
for /f "tokens=1,* delims==" %%A in ('findstr /b "JAVA_VERSION=" "%~1\release"') do set "CANDIDATE_VERSION=%%~B"
if not defined CANDIDATE_VERSION exit /b 0
for /f "tokens=1 delims=.-+" %%V in ("%CANDIDATE_VERSION%") do set "CANDIDATE_VERSION=%%V"
if %CANDIDATE_VERSION% LSS 21 exit /b 0
set "JAVA_HOME=%~1"
set "JLINK=%~1\bin\jlink.exe"
exit /b 0

:jdk_ready
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JAVA_HOME=%JAVA_HOME%
exit /b 0

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

rem IDE installations include a complete Maven distribution.
for /d %%D in ("%ProgramFiles%\JetBrains\IntelliJ IDEA*") do (
    if not defined MVN if exist "%%~D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" set "MVN=%%~D\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"
)
for /d %%D in ("%ProgramFiles%\NetBeans*") do (
    if not defined MVN if exist "%%~D\java\maven\bin\mvn.cmd" set "MVN=%%~D\java\maven\bin\mvn.cmd"
)
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
    echo jlink is from Java %JAVA_MAJOR%. SeaDB needs JDK 21 or newer, so the bundled runtime can run the classes.
    exit /b 1
)
findstr /b "OS_ARCH=" "%JAVA_HOME%\release" | findstr "amd64 x86_64" >nul
if errorlevel 1 (
    echo An x64 JDK is required for the in-process launcher.
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
"%JBIN%jdeps.exe" --ignore-missing-deps --print-module-deps --multi-release 21 "%~dp0seadb-cli\target\seadb-cli-%VERSION%.jar" "%~dp0seadb-backup\target\seadb-backup-%VERSION%.jar" "%~dp0seadb-dbtran\target\seadb-dbtran-%VERSION%.jar" "%~dp0seadb-dbsrv\target\seadb-dbsrv-%VERSION%.jar" "%~dp0seadb-dbeng\target\seadb-dbeng-%VERSION%.jar" "%~dp0seadb-studio\target\seadb-studio-%VERSION%.jar" > "%STAGE%\modules.txt"
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
exit /b 0

:icon
echo.
echo Writing the icon...
set "ICON=%STAGE%\seadb.ico"
"%JBIN%javac.exe" -encoding UTF-8 -d "%STAGE%\icons" "%~dp0packaging\IconWriter.java" "%~dp0seadb-engine\src\main\java\io\github\gabrielmmoraes1999\seadb\SeaIcon.java"
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
> "%STAGE%\AppInfo.cs" echo using System.Reflection;
>> "%STAGE%\AppInfo.cs" echo [assembly: AssemblyTitle("%MODULE%")]
>> "%STAGE%\AppInfo.cs" echo [assembly: AssemblyProduct("SeaDB")]
>> "%STAGE%\AppInfo.cs" echo [assembly: AssemblyDescription("%MODULE%")]
>> "%STAGE%\AppInfo.cs" echo [assembly: AssemblyVersion("%VERSION%.0.0")]
"%CSC%" /nologo /platform:x64 /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll /optimize+ /target:%TARGET% %DEFINE% %WINFORMS% /win32icon:"%ICON%" /resource:"%APPJAR%",AppJar /out:"%OUT%" "%~dp0packaging\SeaDBLauncher.cs" "%STAGE%\AppInfo.cs"
if errorlevel 1 (
    echo Could not embed the JAR for %MODULE%.
    exit /b 1
)
exit /b 0

:cleanup
if exist "%STAGE%" rmdir /s /q "%STAGE%"
exit /b 0
