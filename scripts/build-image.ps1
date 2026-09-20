<#
.SYNOPSIS
    Builds the artemis-browser image and loads it into the local KinD cluster.

.DESCRIPTION
    There is no image registry in this setup, so the image has to be put directly into the cluster's
    nodes with `kind load`. That is also why the tag matters: an image tagged `latest` invites
    Kubernetes to go looking for a registry that does not exist, so the chart pins an explicit
    version and pulls IfNotPresent.

.EXAMPLE
    ./scripts/build-image.ps1
    ./scripts/build-image.ps1 -Tag 0.2.0 -ClusterName my-cluster -SkipTests
#>
param(
    [string]$Tag = "0.1.0",
    [string]$ClusterName = "claude-local",
    [string]$ImageName = "artemis-browser",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root

try
{
    Write-Host "==> Packaging the jar" -ForegroundColor Cyan
    $mvnArgs = @("clean", "package")
    if ($SkipTests) { $mvnArgs += "-DskipTests" }
    mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    Write-Host "==> Building $ImageName`:$Tag" -ForegroundColor Cyan
    docker build -t "$ImageName`:$Tag" .
    if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

    Write-Host "==> Loading it into KinD cluster '$ClusterName'" -ForegroundColor Cyan
    kind load docker-image "$ImageName`:$Tag" --name $ClusterName
    if ($LASTEXITCODE -ne 0) { throw "kind load failed" }

    Write-Host ""
    Write-Host "$ImageName`:$Tag is on every node of '$ClusterName'." -ForegroundColor Green
    Write-Host "A running Deployment does not pick this up on its own - restart it:" -ForegroundColor Green
    Write-Host "    kubectl rollout restart deploy/artemis-browser -n artemis-browser"
}
finally
{
    Pop-Location
}
