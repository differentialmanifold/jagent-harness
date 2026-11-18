$ErrorActionPreference = 'Stop'
$base = if ($env:JAGENT_INSTALL_DIR) { $env:JAGENT_INSTALL_DIR } else { "$env:LOCALAPPDATA/JAgent" }
if (!(Test-Path -LiteralPath "$base/current")) {
    Write-Host "No global JAgent installation at $base."
    return
}
foreach ($path in $base,"$base/bin","$base/releases","$base/current","$base/path-entries") {
    if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Unexpected link at $path."
    }
}
if ((Get-Content -LiteralPath "$base/current" -Raw).Trim() -cnotmatch '^app\.[a-f0-9]{32}$') {
    throw "Unrecognized JAgent installation at $base."
}

foreach ($slot in Get-ChildItem -LiteralPath "$base/releases" -Directory) {
    if ($slot.Name -cmatch '^app\.[a-f0-9]{32}$' -and !($slot.Attributes -band [IO.FileAttributes]::ReparsePoint) -and (Test-Path -LiteralPath "$($slot.FullName)/runner/runner.jar" -PathType Leaf)) {
        foreach ($part in 'runner','server','runners','console','scripts','sources','distribution.json','.env.example') {
            $item = Join-Path $slot.FullName $part
            if (Test-Path -LiteralPath $item) { Remove-Item -LiteralPath $item -Recurse -Force }
        }
        if (!(Get-ChildItem -LiteralPath $slot.FullName -Force)) { [IO.Directory]::Delete($slot.FullName) }
        else { Write-Host "Retained additional files in $($slot.FullName)." }
    }
}
# An installation made before ownership was recorded has no path-entries file.
if (!(Test-Path -LiteralPath "$base/path-entries") -or (Get-Content -LiteralPath "$base/path-entries" -Raw) -eq 'added') {
    $bin = [IO.Path]::GetFullPath("$base/bin")
    $path = [Environment]::GetEnvironmentVariable('Path','User')
    if ($path -and ($path -split ';') -contains $bin) {
        $remaining = (($path -split ';') | Where-Object { $_ -ne $bin }) -join ';'
        [Environment]::SetEnvironmentVariable('Path', $remaining, 'User')
    }
}
foreach ($file in "$base/bin/jagent.cmd","$base/path-entries","$base/current") {
    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
}
foreach ($dir in "$base/bin","$base/releases",$base) {
    if ((Test-Path -LiteralPath $dir) -and !(Get-ChildItem -LiteralPath $dir -Force)) { [IO.Directory]::Delete($dir) }
}
Write-Host 'JAgent uninstalled. Your settings, credentials and workspaces are retained. Open a new terminal.'
