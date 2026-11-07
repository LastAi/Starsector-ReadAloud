# Downloads the uv binary (https://github.com/astral-sh/uv) into runtime\uv and
# checks its SHA-256 against the published checksum. Called by start.cmd.
param(
    [Parameter(Mandatory = $true)][string]$Dest,
    [Parameter(Mandatory = $true)][string]$Version
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x86_64' }
$file = "uv-$arch-pc-windows-msvc.zip"
$url = "https://github.com/astral-sh/uv/releases/download/$Version/$file"
$tmp = Join-Path $Dest 'download'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$zip = Join-Path $tmp $file

Write-Host "Downloading uv $Version ($file)..."
Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
# Downloaded to a file: GitHub serves it as binary, so .Content would be bytes, not text.
$sumFile = "$zip.sha256"
Invoke-WebRequest -Uri "$url.sha256" -OutFile $sumFile -UseBasicParsing
$expected = ((Get-Content -Raw -LiteralPath $sumFile).Trim() -split '\s+')[0].ToLower()
if ($expected -notmatch '^[0-9a-f]{64}$') { throw "unexpected checksum file content: $expected" }
$actual = (Get-FileHash -Algorithm SHA256 -Path $zip).Hash.ToLower()
if ($expected -ne $actual) {
    Remove-Item -Recurse -Force $tmp
    throw "uv download checksum mismatch (expected $expected, got $actual)"
}
Expand-Archive -Path $zip -DestinationPath $tmp -Force
$exe = Get-ChildItem -Path $tmp -Recurse -Filter 'uv.exe' | Select-Object -First 1
if ($null -eq $exe) { throw 'uv.exe not found in the download' }
Copy-Item $exe.FullName (Join-Path $Dest 'uv.exe') -Force
Remove-Item -Recurse -Force $tmp
Write-Host 'uv is ready.'
