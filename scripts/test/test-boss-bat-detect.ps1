#!/usr/bin/env pwsh
<#
.SYNOPSIS
Security and routing regression tests for boss.bat :detect_and_route (#1570).

The old classifier expanded the caller-controlled argument into
`echo %arg% | findstr ...`. cmd.exe parses the expansion before it starts
either process, so &, |, >, < and ^ became shell syntax rather than data.

Source checks run on every platform. Live probes run on Windows and inline
the current subroutines, replacing only `start` with `echo` so no application
or protocol handler is launched. The final mutation proves the sentinel
assertion fails against the vulnerable command shape.
#>

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$batPath = Join-Path $repoRoot 'boss.bat'
$bat = Get-Content $batPath -Raw
$lines = Get-Content $batPath

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        Write-Error "ASSERTION FAILED: $Message"
        exit 1
    }
    Write-Output "ok - $Message"
}

function Get-Subroutine {
    param(
        [string]$Label,
        [string]$EndLabel = ''
    )

    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -eq ":$Label") {
            $start = $i
            break
        }
    }
    $null = Assert-True ($start -ge 0) ":$Label exists in boss.bat"

    $end = $lines.Count - 1
    if ($EndLabel) {
        for ($i = $start + 1; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -eq ":$EndLabel") {
                $end = $i - 1
                break
            }
        }
    }
    return ($lines[$start..$end] -join "`r`n")
}

# --- Source-shape contract (runs without cmd.exe) -------------------------

$detectClassifier = Get-Subroutine -Label 'detect_and_route' -EndLabel 'detect_url'

Assert-True (-not ($detectClassifier -match '(?im)^\s*echo\s+%arg%\s*\|')) `
    'auto-detection never expands the argument into an echo pipeline'
Assert-True (-not ($detectClassifier -match '(?im)^\s*echo[^\r\n]*%arg%')) `
    'failure/help diagnostics never re-expand the untrusted argument'
Assert-True (-not ($detectClassifier -match '(?im)^(?!\s*REM\b)[^\r\n]*\bfindstr\b')) `
    'auto-detection no longer needs a subprocess classifier'

Assert-True ($detectClassifier -match '(?im)^if /i "%arg:~0,7%"=="http://" goto :detect_url\r?$') `
    'HTTP detection compares the bounded prefix inside quotes'
Assert-True ($detectClassifier -match '(?im)^if /i "%arg:~0,8%"=="https://" goto :detect_url\r?$') `
    'HTTPS detection compares the bounded prefix inside quotes'

foreach ($suffix in @('com', 'org', 'net', 'io', 'dev')) {
    $expected = "if /i not `"%arg:.$suffix=%`"==`"%arg%`" goto :detect_domain"
    Assert-True ($detectClassifier -match "(?im)^$([regex]::Escape($expected))\r?$") `
        ".$suffix detection uses quoted substitution rather than a shell pipeline"
}

# --- Live behavior contract (Windows only) -------------------------------

if ($env:OS -ne 'Windows_NT' -or -not (Get-Command cmd.exe -ErrorAction SilentlyContinue)) {
    Write-Output 'ok - cmd.exe unavailable, live auto-detection probes skipped (source-shape checks passed)'
    exit 0
}

$detectBlock = Get-Subroutine -Label 'detect_and_route'
$urlencodeBlock = Get-Subroutine -Label 'urlencode' -EndLabel 'detect_and_route'
$detectWithEcho = [regex]::Replace(
    $detectBlock,
    'start\s+""\s+"boss://([^"]*)"',
    'echo boss://$1',
    [System.Text.RegularExpressions.RegexOptions]::Multiline
)

function Invoke-DetectProbe {
    param(
        [string]$Name,
        [string]$BatchArgument,
        [string]$Marker
    )

    $probe = Join-Path $env:TEMP ("boss-detect-$Name-" + [guid]::NewGuid().ToString('N') + '.cmd')
    $body = @"
@echo off
setlocal DisableDelayedExpansion
if exist "$Marker" del "$Marker"
call :detect_and_route $BatchArgument
set "PROBE_EXIT=%ERRORLEVEL%"
if exist "$Marker" (echo PROBE_MARKER=PRESENT) else (echo PROBE_MARKER=ABSENT)
echo PROBE_EXIT=%PROBE_EXIT%
goto :probe_done
$detectWithEcho
$urlencodeBlock
:probe_done
endlocal
"@
    Set-Content -Path $probe -Value $body -Encoding Ascii

    try {
        return @(& cmd.exe /d /c $probe 2>&1 | ForEach-Object { "$_" })
    } finally {
        Remove-Item $probe -ErrorAction SilentlyContinue
    }
}

function Assert-SafeNoMatch {
    param(
        [string]$Name,
        [string]$BatchArgument,
        [string]$Marker = ''
    )

    $probeMarker = if ($Marker) {
        $Marker
    } else {
        Join-Path $env:TEMP ("boss-detect-marker-$Name-" + [guid]::NewGuid().ToString('N'))
    }
    try {
        $output = Invoke-DetectProbe -Name $Name -BatchArgument $BatchArgument -Marker $probeMarker
        Assert-True (-not (Test-Path $probeMarker)) "$Name cannot create a side-command marker"
        Assert-True ($output -contains 'PROBE_MARKER=ABSENT') `
            "$Name remains data during cmd parsing (output: $($output -join ' | '))"
        Assert-True ($output -contains 'PROBE_EXIT=1') `
            "$Name follows the deterministic no-match path (output: $($output -join ' | '))"
    } finally {
        Remove-Item $probeMarker -ErrorAction SilentlyContinue
    }
}

# Each argument is quoted at the caller boundary, as a user or process would
# pass a single value to boss.bat. The vulnerable echo expansion removed those
# protective quotes on its second parse and activated the operators below.
$ampMarker = Join-Path $env:TEMP ("boss-detect-amp-" + [guid]::NewGuid().ToString('N'))
Assert-SafeNoMatch -Name 'ampersand' -BatchArgument "`"plain&echo injected>$ampMarker&rem`"" -Marker $ampMarker
$pipeMarker = Join-Path $env:TEMP ("boss-detect-pipe-" + [guid]::NewGuid().ToString('N'))
Assert-SafeNoMatch -Name 'pipe' -BatchArgument "`"plain|echo injected>$pipeMarker|rem`"" -Marker $pipeMarker
$redirectMarker = Join-Path $env:TEMP ("boss-detect-redirect-" + [guid]::NewGuid().ToString('N'))
Assert-SafeNoMatch -Name 'redirect' -BatchArgument "`"plain>$redirectMarker`"" -Marker $redirectMarker
Assert-SafeNoMatch -Name 'caret' -BatchArgument '"plain^^caret"'
Assert-SafeNoMatch -Name 'quote' -BatchArgument '"plain""quote"'
$quoteAmpMarker = Join-Path $env:TEMP ("boss-detect-quote-amp-" + [guid]::NewGuid().ToString('N'))
Assert-SafeNoMatch -Name 'quote-ampersand' `
    -BatchArgument "`"plain`"`"&echo injected>$quoteAmpMarker&rem`"" `
    -Marker $quoteAmpMarker
Assert-SafeNoMatch -Name 'bang' -BatchArgument '"plain!bang"'

$urlMarker = Join-Path $env:TEMP ("boss-detect-url-" + [guid]::NewGuid().ToString('N'))
$urlOutput = Invoke-DetectProbe -Name 'url-route' -BatchArgument '"https://example.test/a!b?x=1&y=2"' -Marker $urlMarker
$urlLine = $urlOutput | Where-Object { $_ -like 'boss://url?url=*' } | Select-Object -First 1
Assert-True ($null -ne $urlLine) 'HTTPS input routes to boss://url'
if ($null -ne $urlLine) {
    $encoded = $urlLine -replace '^boss://url\?url=', ''
    Assert-True ([Uri]::UnescapeDataString($encoded) -eq 'https://example.test/a!b?x=1&y=2') `
        'HTTPS routing preserves bang and ampersand characters as URL data'
}
Assert-True (-not (Test-Path $urlMarker)) 'HTTPS routing executes no side command'

$domainMarker = Join-Path $env:TEMP ("boss-detect-domain-" + [guid]::NewGuid().ToString('N'))
$domainOutput = Invoke-DetectProbe -Name 'domain-route' -BatchArgument '"example.com/a?x=1&y=2"' -Marker $domainMarker
$domainLine = $domainOutput | Where-Object { $_ -like 'boss://url?url=*' } | Select-Object -First 1
Assert-True ($null -ne $domainLine) 'domain input routes to boss://url'
if ($null -ne $domainLine) {
    $encoded = $domainLine -replace '^boss://url\?url=', ''
    Assert-True ([Uri]::UnescapeDataString($encoded) -eq 'https://example.com/a?x=1&y=2') `
        'domain routing adds HTTPS and preserves ampersands as URL data'
}
Assert-True (-not (Test-Path $domainMarker)) 'domain routing executes no side command'

$quotedUrlMarker = Join-Path $env:TEMP ("boss-detect-quoted-url-" + [guid]::NewGuid().ToString('N'))
$quotedUrlArgument = "`"https://example.test/`"`"&echo injected>$quotedUrlMarker&rem`""
$quotedUrlOutput = Invoke-DetectProbe -Name 'quoted-url-route' -BatchArgument $quotedUrlArgument -Marker $quotedUrlMarker
Assert-True (-not (Test-Path $quotedUrlMarker)) 'a quote plus ampersand in an HTTPS value executes no side command'
Assert-True (@($quotedUrlOutput | Where-Object { $_ -like 'boss://url?url=*' }).Count -eq 1) `
    'a quote plus ampersand in an HTTPS value still produces exactly one URL route'

$quotedDomainMarker = Join-Path $env:TEMP ("boss-detect-quoted-domain-" + [guid]::NewGuid().ToString('N'))
$quotedDomainArgument = "`"example.com/`"`"&echo injected>$quotedDomainMarker&rem`""
$quotedDomainOutput = Invoke-DetectProbe -Name 'quoted-domain-route' -BatchArgument $quotedDomainArgument -Marker $quotedDomainMarker
Assert-True (-not (Test-Path $quotedDomainMarker)) 'a quote plus ampersand in a domain value executes no side command'
Assert-True (@($quotedDomainOutput | Where-Object { $_ -like 'boss://url?url=*' }).Count -eq 1) `
    'a quote plus ampersand in a domain value still produces exactly one URL route'

# Mutation: the vulnerable classifier must create the marker with the same
# ampersand payload. This proves the live assertion is sensitive to #1570,
# rather than merely exercising a batch file that never reaches the sink.
$mutationMarker = Join-Path $env:TEMP ("boss-detect-mutation-" + [guid]::NewGuid().ToString('N'))
$mutationProbe = Join-Path $env:TEMP ("boss-detect-mutation-" + [guid]::NewGuid().ToString('N') + '.cmd')
$mutationBody = @"
@echo off
setlocal DisableDelayedExpansion
if exist "$mutationMarker" del "$mutationMarker"
call :vulnerable "plain&echo injected>$mutationMarker&rem"
goto :mutation_done
:vulnerable
setlocal DisableDelayedExpansion
set "arg=%~1"
echo %arg% | findstr /i "^http://" >nul
endlocal
goto :eof
:mutation_done
endlocal
"@
Set-Content -Path $mutationProbe -Value $mutationBody -Encoding Ascii
try {
    & cmd.exe /d /c $mutationProbe 2>&1 | Out-Null
    Assert-True (Test-Path $mutationMarker) 'mutation check: the old echo pipeline activates the ampersand payload'
} finally {
    Remove-Item $mutationProbe -ErrorAction SilentlyContinue
    Remove-Item $mutationMarker -ErrorAction SilentlyContinue
    Remove-Item $ampMarker -ErrorAction SilentlyContinue
    Remove-Item $pipeMarker -ErrorAction SilentlyContinue
    Remove-Item $redirectMarker -ErrorAction SilentlyContinue
    Remove-Item $quoteAmpMarker -ErrorAction SilentlyContinue
    Remove-Item $urlMarker -ErrorAction SilentlyContinue
    Remove-Item $domainMarker -ErrorAction SilentlyContinue
    Remove-Item $quotedUrlMarker -ErrorAction SilentlyContinue
    Remove-Item $quotedDomainMarker -ErrorAction SilentlyContinue
}

Write-Output 'ALL boss.bat auto-detection tests passed'
