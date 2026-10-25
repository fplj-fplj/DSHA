param(
    [Parameter(Mandatory=$true)][string]$GoExe,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$env:PATH = (Split-Path -Parent $GoExe) + ';' + (Join-Path $JavaHome 'bin') + ';' + $env:PATH
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_NDK_HOME = Join-Path $AndroidSdk 'ndk\26.3.11579264'
$env:GOPATH = Join-Path $outputRoot 'gopath'
$env:GOCACHE = Join-Path $outputRoot 'gocache'
$env:GOTOOLCHAIN = 'local'
$env:CGO_ENABLED = '1'
$env:PATH = (Join-Path $env:GOPATH 'bin') + ';' + $env:PATH
$mobileVersion = 'v0.0.0-20261011034528-197770444867'
Push-Location $PSScriptRoot
try {
    $goVersion = (& $GoExe env GOVERSION).Trim()
    if ($goVersion -ne 'go1.27.3') { throw 'The audited Android build requires pinned Go 1.27.3' }
    $goRootPath = (& $GoExe env GOROOT).Trim()
    $netVersion = [regex]::Match((Get-Content -Raw -LiteralPath 'go.mod'), 'golang.org/x/net\s+(v[^\s]+)').Groups[1].Value
    $layoutPaths = @((Join-Path $goRootPath 'src\net\interface.go'),
        (Join-Path $env:GOPATH "pkg\mod\golang.org\x\net@$netVersion\internal\socket\sys_posix.go"),
        (Join-Path $env:GOPATH 'pkg\mod\github.com\wlynxg\anet@v0.0.5\interface_android.go'))
    $layouts = @(); $layoutHashes = @{}
    foreach ($layoutPath in $layoutPaths) {
        $layoutSource = Get-Content -Raw -LiteralPath $layoutPath
        $layout = [regex]::Match($layoutSource, '(?s)type ipv6ZoneCache struct\s*\{(.*?)\}').Groups[1].Value
        if (!$layout) { throw 'Missing audited IPv6 zone cache layout' }
        $layouts += ([regex]::Replace(([regex]::Replace($layout, '//[^\r\n]*', '')), '\s+', ''))
        $layoutHashes[$layoutPath] = (Get-FileHash -Algorithm SHA256 -LiteralPath $layoutPath).Hash.ToLower()
    }
    if ($layouts[0] -ne $layouts[1] -or $layouts[0] -ne $layouts[2]) { throw 'anet zone cache layout differs from pinned Go/x-net; refuse linkname build' }
    @{goVersion=$goVersion;anetVersion='v0.0.5';layout=$layouts[0];sourceSha256=$layoutHashes;
        upstreamRequirement='https://github.com/wlynxg/anet#how-to-build-with-go-1230-or-later'} |
        ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $outputRoot 'android-linkname-layout.json')
    & $GoExe install "golang.org/x/mobile/cmd/gomobile@$mobileVersion"
    if ($LASTEXITCODE -ne 0) { throw 'gomobile installation failed' }
    & $GoExe install "golang.org/x/mobile/cmd/gobind@$mobileVersion"
    if ($LASTEXITCODE -ne 0) { throw 'gobind installation failed' }
    & (Join-Path $env:GOPATH 'bin\gomobile.exe') init
    if ($LASTEXITCODE -ne 0) { throw 'gomobile initialization failed' }
    $bindArguments = @('bind', '-target=android/arm64', '-androidapi=30',
        '-javapkg=com.deepseekharness.connect', '-trimpath',
        '-ldflags=-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384',
        '-o', (Join-Path $outputRoot 'dsha-connect.aar'), './mobile')
    & (Join-Path $env:GOPATH 'bin\gomobile.exe') @bindArguments
    if ($LASTEXITCODE -ne 0) { throw 'Android binding failed' }
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $outputRoot 'dsha-connect.aar')
} finally { Pop-Location }
