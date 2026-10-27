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
        function Wait-Docker {}
        function Assert-WindowsDesktop {}
        function Get-BackendUrl { return 'http://127.0.0.1:8080' }
        function Get-Java21 { throw 'Java should not be prepared before backend is ready' }
        function Invoke-Native {
            param($File, $Arguments, [switch]$Capture)
            $script:Calls += $Arguments -join ' '
            if ($Arguments -contains 'up') { throw 'backend startup failed' }
        }
        Assert-Throws { Invoke-FixFlow -Root $root } 'backend startup failed'
        Assert-That ($script:Calls.Count -eq 2) 'Startup continued after Compose failure'
    }
    Test-Case 'One launch waits for services, installs desktop dependencies, then opens JavaFX' {
        $script:Calls = @()
        function Wait-Docker {}
        function Assert-WindowsDesktop {}
        function Get-Java21 { return $root }
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
