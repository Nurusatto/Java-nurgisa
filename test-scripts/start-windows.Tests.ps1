# No Pester dependency: run with pwsh -NoProfile -File test-scripts/start-windows.Tests.ps1.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../scripts/start-windows.ps1')
$root = Join-Path ([IO.Path]::GetTempPath()) ('fixflow launcher ' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $root
$settings = Join-Path $root '.env'
Set-Content -LiteralPath (Join-Path $root '.env.example') -Value 'POSTGRES_PASSWORD=fictional-test-only'
$script:Passed = 0
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:PATH
$originalApi = $env:FIXFLOW_API_URL

function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Body, [string]$MessagePart)
    try { & $Body } catch {
        Assert-That ($_.Exception.Message.Contains($MessagePart)) ('Unexpected error: ' + $_.Exception.Message)
        return
    }
    throw "Expected an error containing: $MessagePart"
}

function Test-Case {
    param([string]$Name, [scriptblock]$Body)
    & $Body
    $script:Passed++
    Write-Host "PASS: $Name"
}

try {
    Test-Case 'Creates missing .env, preserving existing settings on subsequent launches' {
        Initialize-Settings $root
        Assert-That ((Get-Content -LiteralPath $settings -Raw) -match 'fictional-test-only') 'Example was not copied'
        Set-Content -LiteralPath $settings -Value 'POSTGRES_PASSWORD=changed-locally'
        Initialize-Settings $root
        Assert-That ((Get-Content -LiteralPath $settings -Raw) -match 'changed-locally') '.env was overwritten'
    }
    Test-Case 'Uses the published backend port, without printing Compose environment secrets' {
        function Invoke-Native {
            return '{"services":{"backend":{"environment":{"POSTGRES_PASSWORD":"do-not-print"},"ports":[{"target":8080,"published":"18082"}]}}}'
        }
        $url = Get-BackendUrl
        Assert-That ($url -eq 'http://127.0.0.1:18082') 'Published port was ignored'
    }
    Test-Case 'Invalid published port is rejected' {
        function Invoke-Native { return '{"services":{"backend":{"ports":[{"target":8080,"published":"not-a-port"}]}}}' }
        Assert-Throws { Get-BackendUrl } 'Cannot determine'
    }
    Test-Case 'Native failure propagates instead of continuing' {
        $executable = (Get-Process -Id $PID).Path
        Assert-Throws { Invoke-Native -File $executable -Arguments @('-NoProfile', '-Command', 'exit 23') } 'exit 23'
    }
    Test-Case 'Stopping preserves data and does not invoke Maven' {
        $script:Calls = @()
        function Wait-Docker { throw 'Stop must not start Docker Desktop' }
        function Invoke-Native {
            param($File, $Arguments, [switch]$Capture)
            $script:Calls += $File + ' ' + ($Arguments -join ' ')
        }
        Invoke-FixFlow -Root $root -Stop
        Assert-That ($script:Calls.Count -eq 1 -and $script:Calls[0] -eq 'docker compose stop') 'Stop must only stop services'
        Assert-That ((Get-Content -LiteralPath $settings -Raw) -match 'changed-locally') 'Stop altered settings'
    }
    Test-Case 'An unhealthy backend prevents JavaFX from launching' {
        $script:Calls = @()
        $script:DiagnosticsShown = $false
        function Wait-Docker {}
        function Assert-WindowsDesktop {}
        function Show-StartupDiagnostics { $script:DiagnosticsShown = $true }
        function Get-BackendUrl { return 'http://127.0.0.1:8080' }
        function Get-Java21 { throw 'Java should not be prepared before backend is ready' }
        function Invoke-Native {
            param($File, $Arguments, [switch]$Capture)
            $script:Calls += $Arguments -join ' '
            if ($Arguments -contains 'up') { throw 'backend startup failed' }
        }
        Assert-Throws { Invoke-FixFlow -Root $root } 'backend startup failed'
        Assert-That ($script:Calls.Count -eq 2) 'Startup continued after Compose failure'
        Assert-That $script:DiagnosticsShown 'Startup error did not show backend diagnostics'
    }
    Test-Case 'Startup diagnostics show status and backend logs without inspecting environment secrets' {
        $script:Calls = @()
        function Invoke-Native {
            param($File, $Arguments, [switch]$Capture)
            $script:Calls += $File + ' ' + ($Arguments -join ' ')
        }
        Show-StartupDiagnostics
        Assert-That ($script:Calls.Count -eq 2) 'Unexpected diagnostic command count'
        Assert-That ($script:Calls[0] -eq 'docker compose ps --all') 'Container status was not requested'
        Assert-That ($script:Calls[1] -eq 'docker compose logs --no-color --tail 120 backend') 'Backend logs were not requested'
    }
    Test-Case 'Unavailable diagnostics do not mask the original startup failure' {
        function Invoke-Native { throw 'diagnostic command failed' }
        Show-StartupDiagnostics
    }
    Test-Case 'Java PATH shims resolve to the actual JDK root, not their parent directory' {
        $env:JAVA_HOME = ''
        $fixture = Join-Path $root 'java shim reproduction'
        $shimDirectory = Join-Path $fixture 'Oracle/Java/javapath'
        $jdkRoot = Join-Path $fixture 'actual JDK 21'
        $null = New-Item -ItemType Directory -Force -Path $shimDirectory, (Join-Path $jdkRoot 'bin')
        $shimJava = Join-Path $shimDirectory 'java.exe'
        $jdkJava = Join-Path $jdkRoot 'bin/java.exe'
        foreach ($file in @($shimJava, (Join-Path $shimDirectory 'javac.exe'), $jdkJava, (Join-Path $jdkRoot 'bin/javac.exe'))) {
            Set-Content -LiteralPath $file -Value 'test executable'
        }
        function Get-Command { return [pscustomobject]@{ Source = $shimJava } }
        function Test-Java21 { param($Java); return $Java -eq $shimJava -or $Java -eq $jdkJava }
        function Get-JavaRuntimeHome { param($Java); return $jdkRoot }
        function Invoke-RestMethod { throw 'An installed JDK must not be downloaded again' }
        $selected = Get-Java21 $fixture
        Assert-That ($selected -eq $jdkRoot) 'JAVA_HOME was inferred from a shim directory'
        Assert-That (Test-Path -LiteralPath (Join-Path $selected 'bin/java.exe')) 'Selected JAVA_HOME has no bin/java.exe'
    }
    Test-Case 'Runtime home is read from a real JVM even though settings use stderr' {
        $nativeJava = Get-Command java -CommandType Application -ErrorAction Stop | Select-Object -First 1
        $reportedRoot = Get-JavaRuntimeHome $nativeJava.Source
        Assert-That (-not [string]::IsNullOrWhiteSpace($reportedRoot)) 'JVM did not report java.home'
        $executable = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'java.exe' } else { 'java' }
        Assert-That (Test-Path -LiteralPath (Join-Path $reportedRoot "bin/$executable")) 'Reported runtime directory is unusable'
    }
    Test-Case 'An unusable runtime directory from PATH falls back to the complete cached JDK' {
        $env:JAVA_HOME = ''
        $fixture = Join-Path $root 'bad runtime fallback'
        $shimDirectory = Join-Path $fixture 'javapath'
        $incompleteRoot = Join-Path $fixture 'incomplete JDK'
        $cache = Join-Path $fixture '.local/runtime/jdk-21'
        $null = New-Item -ItemType Directory -Force -Path $shimDirectory, (Join-Path $incompleteRoot 'bin'), (Join-Path $cache 'bin')
        $shimJava = Join-Path $shimDirectory 'java.exe'
        foreach ($file in @($shimJava, (Join-Path $shimDirectory 'javac.exe'), (Join-Path $incompleteRoot 'bin/java.exe'), (Join-Path $cache 'bin/java.exe'), (Join-Path $cache 'bin/javac.exe'))) {
            Set-Content -LiteralPath $file -Value 'test executable'
        }
        function Get-Command { return [pscustomobject]@{ Source = $shimJava } }
        function Test-Java21 {
            param($Java)
            return (Test-Path -LiteralPath $Java) -and (Test-Path -LiteralPath (Join-Path (Split-Path -Parent $Java) 'javac.exe'))
        }
        function Get-JavaRuntimeHome {
            param($Java)
            if ($Java -eq $shimJava) { return $incompleteRoot }
            return $cache
        }
        function Invoke-RestMethod { throw 'The complete cached JDK should be reused' }
        Assert-That ((Get-Java21 $fixture) -eq $cache) 'Incomplete runtime was accepted or cache ignored'
    }
    Test-Case 'A JAVA_HOME value surrounded by quotes is normalized before checking it' {
        $jdkRoot = Join-Path $root 'quoted JDK root'
        $null = New-Item -ItemType Directory -Force -Path (Join-Path $jdkRoot 'bin')
        $jdkJava = Join-Path $jdkRoot 'bin/java.exe'
        $env:JAVA_HOME = '"' + $jdkRoot + '"'
        function Get-Command { return $null }
        function Test-Java21 { param($Java); return $Java -eq $jdkJava }
        function Get-JavaRuntimeHome { return $jdkRoot }
        function Invoke-RestMethod { throw 'A valid JAVA_HOME must not trigger a download' }
        Assert-That ((Get-Java21 $root) -eq $jdkRoot) 'Quoted JAVA_HOME was not normalized'
    }
    Test-Case 'An invalid selected JAVA_HOME prevents Maven from launching' {
        $script:Calls = @()
        function Wait-Docker {}
        function Assert-WindowsDesktop {}
        function Get-Java21 { return (Join-Path $root 'not-a-jdk') }
        function Test-Java21 { return $false }
        function Get-BackendUrl { return 'http://127.0.0.1:18082' }
        function Invoke-Native {
            param($File, $Arguments, [switch]$Capture)
            $script:Calls += $File
        }
        Assert-Throws { Invoke-FixFlow -Root $root } 'Selected JDK 21 is unusable'
        Assert-That ($script:Calls.Count -eq 2 -and @($script:Calls | Where-Object { $_ -ne 'docker' }).Count -eq 0) 'Maven was called with an invalid JAVA_HOME'
    }
    Test-Case 'One launch waits for services, installs desktop dependencies, then opens JavaFX' {
        $script:Calls = @()
        function Wait-Docker {}
        function Assert-WindowsDesktop {}
        function Get-Java21 { return $root }
        function Test-Java21 { return $true }
        function Get-BackendUrl { return 'http://127.0.0.1:18082' }
        function Invoke-Native {
            param($File, $Arguments, [switch]$Capture)
            $script:Calls += ,@($Arguments)
        }
        Invoke-FixFlow -Root $root
        Assert-That ($script:Calls.Count -eq 4) 'Unexpected command count'
        Assert-That ($script:Calls[1] -contains '--wait') 'Compose did not wait for readiness'
        Assert-That ($script:Calls[2] -contains '-am' -and $script:Calls[2] -contains 'install') 'contracts were not installed'
        Assert-That ($script:Calls[3] -contains 'javafx:run') 'JavaFX was not started last'
        Assert-That ($env:FIXFLOW_API_URL -eq 'http://127.0.0.1:18082') 'Desktop URL does not match Docker port'
    }
    Test-Case 'JDK checksum failure prevents extraction and execution' {
        $script:Expanded = $false
        function Test-Java21 { return $false }
        function Invoke-RestMethod {
            return @{ version = @{ major = 21 }; binary = @{ package = @{
                checksum = ('0' * 64); link = 'https://github.com/adoptium/temurin21-binaries/releases/download/test/jdk.zip'
            } } }
        }
        function Invoke-WebRequest {
            param($Uri, $OutFile, $TimeoutSec, $UserAgent, [switch]$UseBasicParsing)
            Set-Content -LiteralPath $OutFile -Value 'corrupted download'
        }
        function Expand-Archive { $script:Expanded = $true }
        Assert-Throws { Get-Java21 $root } 'checksum mismatch'
        Assert-That (-not $script:Expanded) 'Unverified download was extracted'
        Assert-That (@(Get-ChildItem -LiteralPath (Join-Path $root '.local/runtime') -Filter 'download-*').Count -eq 0) 'Download staging was left behind'
    }
    Test-Case 'A verified JDK is installed locally, preserving a previous incomplete cache' {
        $packageRoot = Join-Path $root 'package/jdk-demo/bin'
        $null = New-Item -ItemType Directory -Path $packageRoot -Force
        Set-Content -LiteralPath (Join-Path $packageRoot 'java.exe') -Value 'test executable'
        Set-Content -LiteralPath (Join-Path $packageRoot 'javac.exe') -Value 'test compiler'
        $zip = Join-Path $root 'verified-jdk.zip'
        Compress-Archive -Path (Join-Path $root 'package/jdk-demo') -DestinationPath $zip
        $script:FixtureChecksum = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
        $cache = Join-Path $root '.local/runtime/jdk-21'
        $null = New-Item -ItemType Directory -Path $cache -Force
        Set-Content -LiteralPath (Join-Path $cache 'keep.txt') -Value 'preserve this file'
        function Test-Java21 { param($Java); return ($Java -match 'expanded' -and (Test-Path -LiteralPath $Java)) }
        function Invoke-RestMethod {
            return @{ version = @{ major = 21 }; binary = @{ package = @{
                checksum = $script:FixtureChecksum; link = 'https://github.com/adoptium/temurin21-binaries/releases/download/test/jdk.zip'
            } } }
        }
        function Invoke-WebRequest {
            param($Uri, $OutFile, $TimeoutSec, $UserAgent, [switch]$UseBasicParsing)
            Copy-Item -LiteralPath $zip -Destination $OutFile
        }
        $installed = Get-Java21 $root
        Assert-That (Test-Path -LiteralPath (Join-Path $installed 'bin/java.exe')) 'JDK was not cached'
        Assert-That (@(Get-ChildItem -LiteralPath (Split-Path $cache -Parent) -Filter 'jdk-21-previous-*').Count -eq 1) 'Previous cache was destroyed'
    }
    Write-Host "Launcher checks: $script:Passed passed"
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:PATH = $originalPath
    $env:FIXFLOW_API_URL = $originalApi
    Remove-Item -LiteralPath $root -Recurse -Force
}
