$ErrorActionPreference = 'Stop'
$src = Split-Path $PSScriptRoot
$base = if ($env:JAGENT_INSTALL_DIR) { $env:JAGENT_INSTALL_DIR } else { "$env:LOCALAPPDATA/JAgent" }
$state = if ($env:JAGENT_STATE_DIR) { $env:JAGENT_STATE_DIR } else { "$env:USERPROFILE/.jagent" }
$id = 'app.' + [Guid]::NewGuid().ToString('N')
$slot = "$base/releases/$id"
$bin = [IO.Path]::GetFullPath("$base/bin")
New-Item -ItemType Directory -Force -Path $slot,$bin,$state | Out-Null
$newInstall = !(Test-Path -LiteralPath "$base/current")
try {
    if (Test-Path "$src/pom.xml") {
        & node "$src/scripts/package.mjs" --output "$slot/build"
        if ($LASTEXITCODE -ne 0) { throw 'Package build failed' }
        $version = ([xml](Get-Content "$src/pom.xml" -Raw)).project.version
        $src = "$slot/build/jagent-$version"
    }
    if (Test-Path "$src/distribution.json") {
        foreach ($part in 'server','runner','runners','console','scripts','sources','distribution.json','.env.example') {
            Copy-Item "$src/$part" $slot -Recurse
        }
        if (!(Test-Path "$state/.env.local")) { Copy-Item "$src/.env.example" "$state/.env.local" }
        Write-Host "Configure the model in $state/.env.local and run jagent."
    } elseif (Test-Path "$src/coding/runner.jar") {
        if ((Test-Path "$base/current") -and (Test-Path "$base/releases/$(Get-Content "$base/current")/distribution.json")) {
            throw 'Update the installed full application using its full package.'
        }
        New-Item -ItemType Directory -Path "$slot/runner" | Out-Null
        Copy-Item "$src/coding/runner.jar" "$slot/runner/"
    } else { throw 'Extract the full application or Runner ZIP first.' }
    Copy-Item "$src/scripts/jagent.cmd" "$bin/jagent.cmd" -Force
    if (Test-Path "$slot/build") { Remove-Item "$slot/build" -Recurse -Force }
    [IO.File]::WriteAllText("$slot/current", $id)
    if (Test-Path "$base/current") { [IO.File]::Replace("$slot/current","$base/current",[System.Management.Automation.Language.NullString]::Value) }
    else { [IO.File]::Move("$slot/current","$base/current") }
} catch { Remove-Item $slot -Recurse -Force; throw }
$path = [Environment]::GetEnvironmentVariable('Path','User')
if (($path -split ';') -notcontains $bin) {
    [Environment]::SetEnvironmentVariable('Path', ("$path;$bin").TrimStart(';'), 'User')
    [IO.File]::WriteAllText("$base/path-entries", 'added')
} elseif ($newInstall) {
    [IO.File]::WriteAllText("$base/path-entries", 'existing')
}
Write-Host "Installed. Open a new terminal and run jagent --help."
