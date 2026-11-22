# PikaStats bootstrap: makes sure Python 3.10+ exists, then starts the setup launcher.
# If Python is missing it is downloaded from python.org and installed only for this Windows user
# into %LOCALAPPDATA%\PikaStats\runtime\python (no admin rights, no PATH changes).
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$InstallDir = Join-Path $env:LOCALAPPDATA 'PikaStats'
$PythonDir = Join-Path $InstallDir 'runtime\python'
$PythonVersion = '3.12.8'
$PythonUrl = "https://www.python.org/ftp/python/$PythonVersion/python-$PythonVersion-amd64.exe"

function Write-Step($text) { Write-Host "  [+] $text" -ForegroundColor Green }
function Write-Warn($text) { Write-Host "  [!] $text" -ForegroundColor Yellow }

function Test-Python($command, [string[]]$prefix) {
    # 64-bit Python 3.10+, and not the Microsoft Store build: that one redirects files written to
    # AppData\Local into a private folder, so the overlay's environment breaks ("failed to locate pyvenv.cfg").
    $check = "import sys; print(sys.version_info >= (3, 10) and sys.maxsize > 2**32 and 'WindowsApps' not in sys.executable)"
    try {
        $output = & $command @prefix -c $check 2>$null
        return ($LASTEXITCODE -eq 0 -and "$output".Trim() -eq 'True')
    } catch {
        return $false
    }
}

function Find-Python {
    $bundled = Join-Path $PythonDir 'python.exe'
    if ((Test-Path $bundled) -and (Test-Python $bundled @())) { return @{ exe = $bundled; prefix = @() } }
    if (Get-Command py -ErrorAction SilentlyContinue) {
        # "py -3" may pick the Store build; try each version so a normal install still wins.
        foreach ($version in @('-3', '-3.13', '-3.12', '-3.11', '-3.10')) {
            if (Test-Python 'py' @($version)) {
                $exe = (& py $version -c "import sys; print(sys.executable)").Trim()
                return @{ exe = $exe; prefix = @() }
            }
        }
    }
    $python = Get-Command python -ErrorAction SilentlyContinue
    # Skip the Microsoft Store placeholder in WindowsApps, which only opens the Store.
    if ($python -and $python.Source -notlike '*WindowsApps*' -and (Test-Python $python.Source @())) {
        return @{ exe = $python.Source; prefix = @() }
    }
    return $null
}

Write-Host ''
Write-Host '  PIKASTATS SETUP' -ForegroundColor Cyan
Write-Host '  checking for Python...' -ForegroundColor DarkGray

$found = Find-Python
if (-not $found) {
    Write-Warn "Python is not installed. Downloading Python $PythonVersion from python.org (about 25 MB)..."
    $installer = Join-Path $env:TEMP "python-$PythonVersion-amd64.exe"
    Invoke-WebRequest -Uri $PythonUrl -OutFile $installer -UseBasicParsing
    Write-Step "Installing Python for this user only into $PythonDir ..."
    New-Item -ItemType Directory -Force -Path $PythonDir | Out-Null
    $arguments = @('/quiet', 'InstallAllUsers=0', 'PrependPath=0', 'Include_launcher=0', 'Include_test=0',
        'Include_doc=0', 'Shortcuts=0', 'AssociateFiles=0', "TargetDir=$PythonDir")
    $process = Start-Process -FilePath $installer -ArgumentList $arguments -Wait -PassThru
    Remove-Item $installer -ErrorAction SilentlyContinue
    if ($process.ExitCode -ne 0) { throw "The Python installer failed (code $($process.ExitCode))." }
    $found = Find-Python
    if (-not $found) { throw 'Python was installed but cannot be started.' }
}
Write-Step "Python: $($found.exe)"

$launcher = Join-Path $PSScriptRoot 'launcher.py'
if (-not (Test-Path $launcher)) { throw "launcher.py is missing next to this script ($PSScriptRoot)." }
$env:PYTHONIOENCODING = 'utf-8'
& $found.exe $launcher --setup @args
exit $LASTEXITCODE
