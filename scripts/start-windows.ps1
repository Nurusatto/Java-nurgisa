#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$Stop,
    [switch]$ServicesOnly,
    [ValidateRange(30, 900)][int]$WaitSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot

function Invoke-Native {
    param([string]$File, [string[]]$Arguments, [switch]$Capture)
    if ($Capture) {
        $output = & $File @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$File failed (exit $LASTEXITCODE)." }
        return ($output -join [Environment]::NewLine)
    }
    & $File @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$File failed (exit $LASTEXITCODE)." }
}

function Initialize-Settings {
    param([string]$Root)
    $settings = Join-Path $Root '.env'
    if (-not (Test-Path -LiteralPath $settings)) {
        Copy-Item -LiteralPath (Join-Path $Root '.env.example') -Destination $settings
        Write-Host 'Created .env with local demo settings.'
    }
}

function Test-DockerReady {
    try {
        $null = & docker info --format '{{.ServerVersion}}' 2>$null
        return $LASTEXITCODE -eq 0
    } catch { return $false }
}

function Wait-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Install Docker Desktop for Windows, then run start.cmd again.'
    }
    if (Test-DockerReady) { return }
    $candidates = @()
    if ($env:ProgramFiles) { $candidates += Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe' }
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe' }
    $desktop = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($desktop) { Start-Process -FilePath $desktop | Out-Null }
    Write-Host 'Waiting for Docker Desktop (Linux containers)...'
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-DockerReady) { return }
        Start-Sleep -Seconds 2
    }
    throw 'Docker is not ready. Open Docker Desktop, enable its WSL 2 / Linux containers engine and retry.'
}

function Test-Java21 {
    param([string]$Java)
    if (-not (Test-Path -LiteralPath $Java)) { return $false }
    $compiler = Join-Path (Split-Path -Parent $Java) 'javac.exe'
    if (-not (Test-Path -LiteralPath $compiler)) { return $false }
    try {
        $version = & $Java --version 2>$null
        if ($LASTEXITCODE -ne 0 -or ($version -join ' ') -notmatch '^(openjdk|java) 21[.\s]') { return $false }
        $compilerVersion = & $compiler --version 2>$null
        return ($LASTEXITCODE -eq 0 -and ($compilerVersion -join ' ') -match '^javac 21[.\s]')
    } catch { return $false }
}

function Get-JavaRuntimeHome {
    param([string]$Java)
    # Read both streams directly: Windows PowerShell 5.1 treats native stderr as errors.
    $process = New-Object System.Diagnostics.Process
    try {
        $process.StartInfo.FileName = $Java
        $process.StartInfo.Arguments = '-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -XshowSettings:properties -version'
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        $process.StartInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
        $process.StartInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
        if (-not $process.Start()) { return $null }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) {
            $process.Kill()
            return $null
        }
        if ($process.ExitCode -ne 0 -or -not $stdout.Wait(2000) -or -not $stderr.Wait(2000)) { return $null }
        $settings = $stderr.Result + [Environment]::NewLine + $stdout.Result
        $match = [regex]::Match($settings, '(?m)^\s*java\.home\s*=\s*([^\r\n]+)')
        if ($match.Success) { return $match.Groups[1].Value.Trim() }
        return $null
    } catch { return $null }
    finally { $process.Dispose() }
}

function Get-Java21 {
    param([string]$Root)
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path ($env:JAVA_HOME.Trim().Trim('"')) 'bin\java.exe' }
    foreach ($name in @('javac.exe', 'java.exe')) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { $candidates += Join-Path (Split-Path -Parent $command.Source) 'java.exe' }
    }
    $cache = Join-Path $Root '.local\runtime\jdk-21'
    $candidates += Join-Path $cache 'bin\java.exe'
    foreach ($java in ($candidates | Select-Object -Unique)) {
        if (Test-Java21 $java) {
            $jdkRoot = Get-JavaRuntimeHome $java
            if ($jdkRoot -and (Test-Java21 (Join-Path $jdkRoot 'bin\java.exe'))) {
                return [IO.Path]::GetFullPath($jdkRoot)
            }
        }
    }
    if (-not [Environment]::Is64BitOperatingSystem -or $env:PROCESSOR_ARCHITECTURE -eq 'ARM64') {
        throw 'Automatic JDK setup supports Windows x64. Install a compatible JDK 21 manually.'
    }
    Write-Host 'Downloading a portable Temurin JDK 21 (first launch only)...'
    $runtime = Join-Path $Root '.local\runtime'
    $null = New-Item -ItemType Directory -Force -Path $runtime
    $staging = Join-Path $runtime ('download-' + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $staging
    $previousProgress = $ProgressPreference
    try {
        $ProgressPreference = 'SilentlyContinue'
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $metadata = @(Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse' -UserAgent 'FixFlow-Launcher/0.1' -TimeoutSec 60)
        $asset = $metadata | Where-Object { $_.version.major -eq 21 } | Select-Object -First 1
        if (-not $asset) { throw 'The official JDK service did not return a Java 21 package.' }
        $package = $asset.binary.package
        if ($package.checksum -notmatch '^[a-fA-F0-9]{64}$' -or
            $package.link -notmatch '^https://github\.com/adoptium/temurin21-binaries/releases/download/') {
            throw 'The official JDK service returned unexpected download metadata.'
        }
        $archive = Join-Path $staging 'jdk.zip'
        Invoke-WebRequest -UseBasicParsing -Uri $package.link -OutFile $archive -UserAgent 'FixFlow-Launcher/0.1' -TimeoutSec 600
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $package.checksum) {
            throw 'JDK checksum mismatch. The downloaded archive will not be executed.'
        }
        $expanded = Join-Path $staging 'expanded'
        Expand-Archive -LiteralPath $archive -DestinationPath $expanded
        $directories = @(Get-ChildItem -LiteralPath $expanded -Directory | Where-Object {
            Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')
        })
        if ($directories.Count -ne 1 -or -not (Test-Java21 (Join-Path $directories[0].FullName 'bin\java.exe'))) {
            throw 'The downloaded package does not contain a working JDK 21.'
        }
        if (Test-Path -LiteralPath $cache) {
            Move-Item -LiteralPath $cache -Destination ($cache + '-previous-' + [Guid]::NewGuid().ToString('N'))
        }
        Move-Item -LiteralPath $directories[0].FullName -Destination $cache
        return $cache
    } finally {
        $ProgressPreference = $previousProgress
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    }
}

function Get-BackendUrl {
    $json = Invoke-Native -File 'docker' -Arguments @('compose', 'config', '--format', 'json') -Capture
    $configuration = $json | ConvertFrom-Json
    $ports = @($configuration.services.backend.ports | Where-Object { $_.target -eq 8080 })
    if ($ports.Count -ne 1 -or [string]$ports[0].published -notmatch '^\d+$') {
        throw 'Cannot determine the backend port from compose.yaml.'
    }
    $port = [int]$ports[0].published
    if ($port -lt 1 -or $port -gt 65535) { throw 'Invalid published backend port.' }
    return "http://127.0.0.1:$port"
}

function Assert-WindowsDesktop {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'The desktop launcher requires Windows. Use Docker Compose directly for backend services.'
    }
}

function Show-StartupDiagnostics {
    Write-Host 'Container status:'
    try {
        Invoke-Native -File 'docker' -Arguments @('compose', 'ps', '--all')
    } catch { Write-Host 'Could not read container status.' }
    Write-Host 'Last backend log messages:'
    try {
        Invoke-Native -File 'docker' -Arguments @('compose', 'logs', '--no-color', '--tail', '120', 'backend')
    } catch { Write-Host 'Could not read backend logs. Run: docker compose logs --tail 120 backend' }
}

function Invoke-FixFlow {
    param([string]$Root, [switch]$Stop, [switch]$ServicesOnly, [int]$WaitSeconds = 240)
    if (-not $Stop -and -not $ServicesOnly) { Assert-WindowsDesktop }
    Push-Location -LiteralPath $Root
    try {
        Initialize-Settings $Root
        if ($Stop) {
            Invoke-Native -File 'docker' -Arguments @('compose', 'stop')
            Write-Host 'Services stopped. PostgreSQL data is preserved.'
            return
        }
        Wait-Docker
        Invoke-Native -File 'docker' -Arguments @('compose', 'version')
        $apiUrl = Get-BackendUrl
        Write-Host 'Building and starting PostgreSQL + FixFlow backend...'
        try {
            Invoke-Native -File 'docker' -Arguments @('compose', 'up', '--detach', '--build', '--wait', '--wait-timeout', [string]$WaitSeconds, 'backend')
        } catch {
            $startupFailure = $_
            Show-StartupDiagnostics
            throw $startupFailure
        }
        Write-Host "Backend ready: $apiUrl"
        if ($ServicesOnly) { return }
        $jdkRoot = Get-Java21 $Root
        if (-not (Test-Java21 (Join-Path $jdkRoot 'bin\java.exe'))) {
            throw "Selected JDK 21 is unusable: $jdkRoot. Expected bin\java.exe and bin\javac.exe."
        }
        $env:JAVA_HOME = [IO.Path]::GetFullPath($jdkRoot)
        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
        $env:FIXFLOW_API_URL = $apiUrl
        Write-Host "Using JDK 21: $env:JAVA_HOME"
        Write-Host 'Preparing JavaFX libraries, then opening FixFlow...'
        $wrapper = Join-Path $Root 'mvnw.cmd'
        Invoke-Native -File $wrapper -Arguments @('-B', '-ntp', '-pl', 'fixflow-desktop', '-am', '-DskipTests', 'install')
        Invoke-Native -File $wrapper -Arguments @('-B', '-ntp', '-pl', 'fixflow-desktop', 'javafx:run')
    } finally { Pop-Location }
}

# Dot-sourcing exposes functions to the regression checks without starting Docker or the UI.
if ($MyInvocation.InvocationName -ne '.') {
    try {
        Invoke-FixFlow -Root $script:ProjectRoot -Stop:$Stop -ServicesOnly:$ServicesOnly -WaitSeconds $WaitSeconds
    } catch {
        Write-Host ('FixFlow: ' + $_.Exception.Message) -ForegroundColor Red
        exit 1
    }
}
