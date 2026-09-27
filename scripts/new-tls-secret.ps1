<#
.SYNOPSIS
    Creates the certificate the ingress shows the browser for artemis-browser, using mkcert.

.DESCRIPTION
    Not the pod's own certificate: the chart mints that one (secret artemis-browser-tls) and only
    the ingress ever sees it. This one is `ingress.tls.secretName` (artemis-browser-ingress-tls),
    which the ingress uses to serve HTTPS to the browser, redirecting HTTP, while it exists.

    One-time prerequisite, done by you (not this script) because it changes what Windows trusts:
        winget install FiloSottile.mkcert
        mkcert -install

    After the first run, upgrade the release once so the ingress picks up its tls block:
        helm upgrade artemis-browser charts/artemis-browser -n artemis-browser --reset-then-reuse-values
    Re-running this script later (to renew) needs no upgrade.

    The key and certificate are written to .tls/ (gitignored) and nowhere else.

.EXAMPLE
    ./scripts/new-tls-secret.ps1
#>
param(
    [string]$HostName = "artemis-browser.claude.local",
    [string]$Namespace = "artemis-browser",
    [string]$SecretName = "artemis-browser-ingress-tls"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not (Get-Command mkcert -ErrorAction SilentlyContinue)) {
    throw "mkcert not found. Install it and trust its CA first: winget install FiloSottile.mkcert; mkcert -install (then open a new terminal)"
}
$caRoot = (mkcert -CAROOT).Trim()
if (-not (Test-Path (Join-Path $caRoot "rootCA.pem"))) {
    throw "mkcert has no local CA yet. Run: mkcert -install"
}

$dir = Join-Path $root ".tls"
New-Item -ItemType Directory -Force $dir | Out-Null
$cert = Join-Path $dir "$HostName.pem"
$key = Join-Path $dir "$HostName-key.pem"

mkcert -cert-file $cert -key-file $key $HostName
if ($LASTEXITCODE -ne 0) { throw "mkcert failed" }

if (-not (kubectl get namespace $Namespace --ignore-not-found -o name)) {
    kubectl create namespace $Namespace
    if ($LASTEXITCODE -ne 0) { throw "kubectl: namespace $Namespace" }
}
kubectl -n $Namespace create secret tls $SecretName --cert=$cert --key=$key --dry-run=client -o yaml | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw "kubectl: secret $SecretName" }

Write-Host "Secret $Namespace/$SecretName holds a certificate for $HostName"
