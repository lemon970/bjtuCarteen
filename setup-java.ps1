param(
    [Parameter(Mandatory = $true)]
    [string] $Archive,

    [Parameter(Mandatory = $true)]
    [string] $Destination
)

$ErrorActionPreference = 'Stop'

$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$javaRoot = Split-Path -Parent $destinationPath
$extractPath = Join-Path $javaRoot ("_extract_" + [System.Guid]::NewGuid().ToString('N'))
$extractMoved = $false

New-Item -ItemType Directory -Force -Path $javaRoot | Out-Null

Get-ChildItem -LiteralPath $javaRoot -Directory -Filter '_extract*' -ErrorAction SilentlyContinue | ForEach-Object {
    $tempDir = $_.FullName
    try {
        Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction Stop
    } catch {
        Write-Warning "Skipping locked temp directory: $tempDir"
    }
}

New-Item -ItemType Directory -Force -Path $extractPath | Out-Null

try {
    Expand-Archive -LiteralPath $archivePath -DestinationPath $extractPath

    $javaExe = Get-ChildItem -LiteralPath $extractPath -Recurse -Filter java.exe |
        Where-Object {
            $_.FullName -match '\\bin\\java\.exe$' -and
            (Test-Path -LiteralPath (Join-Path $_.DirectoryName 'javac.exe'))
        } |
        Select-Object -First 1

    if (-not $javaExe) {
        throw 'Bundled JDK archive does not contain bin\java.exe and bin\javac.exe.'
    }

    $jdkHome = Split-Path -Parent (Split-Path -Parent $javaExe.FullName)

    if (Test-Path -LiteralPath $destinationPath) {
        try {
            Remove-Item -LiteralPath $destinationPath -Recurse -Force -ErrorAction Stop
        } catch {
            throw "Cannot replace existing JDK directory. Close running Java processes under $destinationPath and retry."
        }
    }

    if ([System.IO.Path]::GetFullPath($jdkHome).TrimEnd('\') -ieq [System.IO.Path]::GetFullPath($extractPath).TrimEnd('\')) {
        Move-Item -LiteralPath $extractPath -Destination $destinationPath
        $extractMoved = $true
    } else {
        Move-Item -LiteralPath $jdkHome -Destination $destinationPath
    }

    if (-not (Test-Path -LiteralPath (Join-Path $destinationPath 'bin\java.exe'))) {
        throw 'Prepared JDK does not contain bin\java.exe.'
    }
} finally {
    if (-not $extractMoved -and (Test-Path -LiteralPath $extractPath)) {
        try {
            Remove-Item -LiteralPath $extractPath -Recurse -Force -ErrorAction Stop
        } catch {
            Write-Warning "Could not remove temp directory: $extractPath"
        }
    }
}
