# Smart Energy Android: one-time PERMANENT signing identity.
# Run locally on Windows. Never send this signing key to a chat.
# Requires GitHub CLI authenticated as a repo admin and JDK keytool.
$ErrorActionPreference = 'Stop'
$Repo = 'tapion69/smart-energy-android'
$Alias = 'smart-energy-production'
$Dir = Join-Path $env:USERPROFILE 'Documents\SmartEnergy-SIGNING-BACKUP'
$Store = Join-Path $Dir 'smart-energy-permanent.p12'
$Recovery = Join-Path $Dir 'signing-recovery-KEEP-PRIVATE.txt'
if (!(Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'GitHub CLI is required: winget install GitHub.cli, then gh auth login.'
}
& gh auth status -h github.com
if ($LASTEXITCODE -ne 0) { throw 'Run gh auth login first.' }
$KeytoolCmd = (Get-Command keytool -ErrorAction SilentlyContinue | Select-Object -First 1)
$Keytool = if ($KeytoolCmd) { $KeytoolCmd.Source } else { $null }
if (!$Keytool) {
    $Candidates = @(
       (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe'),
       (Join-Path $env:ProgramFiles 'Java\jdk-21\bin\keytool.exe')
    )
    $Keytool = $Candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (!$Keytool) { throw 'JDK keytool.exe not found. Install or open Android Studio JDK.' }
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
if ((Test-Path $Store) -xor (Test-Path $Recovery)) {
    throw 'Incomplete signing backup. Recover both permanent key and password.'
}
if (!(Test-Path $Store)) {
    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $Pass = [Convert]::ToBase64String($bytes)
    & $Keytool -genkeypair -alias $Alias -keyalg RSA -keysize 3072 -validity 10950 -storetype PKCS12 -keystore $Store -storepass $Pass -keypass $Pass -dname 'CN=Smart Energy Android, OU=Smart Energy, O=Private Project, C=FR' -noprompt
    if ($LASTEXITCODE -ne 0 -or !(Test-Path $Store)) { throw 'Key creation failed' }
    $content = 'ALIAS=' + $Alias + [Environment]::NewLine + 'STORE_PASSWORD=' + $Pass + [Environment]::NewLine + 'KEY_PASSWORD=' + $Pass + [Environment]::NewLine
    [IO.File]::WriteAllText($Recovery,$content)
} else {
    $row = Get-Content $Recovery | Where-Object { $_ -match '^STORE_PASSWORD=' } | Select-Object -First 1
    if (!$row) { throw 'Signing recovery password is missing' }
    $Pass = $row.Substring('STORE_PASSWORD='.Length)
}
# Export the public certificate in PEM format rather than using keytool -list -v.
# Some localized Java runtimes (including French JBR) throw
# MissingFormatArgumentException in keytool's verbose certificate printer.
# The PKCS12 private key and the recovery file are never modified here.
$previousErrorAction = $ErrorActionPreference
$certificateOutput = @()
try {
    $ErrorActionPreference = 'Continue'
    $certificateOutput = @(& $Keytool -exportcert -rfc -storetype PKCS12 -keystore $Store -storepass $Pass -alias $Alias 2>&1 | ForEach-Object { $_.ToString() })
    $keyExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorAction
}
$certificateText = $certificateOutput -join [Environment]::NewLine
if ($keyExit -ne 0) {
    $safeDetails = $certificateText.Replace($Pass,'[REDACTED]').Replace($Store,'[KEYSTORE_PATH]')
    if ($safeDetails.Length -gt 1200) { $safeDetails = $safeDetails.Substring(0,1200) }
    Write-Host ('Keytool used: ' + $Keytool)
    Write-Host ('Keytool exit code: ' + $keyExit)
    Write-Host ('Keytool error (secrets redacted): ' + $safeDetails)
    throw 'Could not export the public signing certificate; existing backup is intact.'
}
$pem = [regex]::Match($certificateText,'(?s)-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\r\\n]+?)\\s*-----END CERTIFICATE-----')
if (!$pem.Success) { throw 'Keytool returned no public certificate (PEM). Existing backup preserved.' }
$encodedCertificate = $pem.Groups[1].Value -replace '\\s',''
$certificateBytes = [Convert]::FromBase64String($encodedCertificate)
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $CertificateSha256 = ([BitConverter]::ToString($sha256.ComputeHash($certificateBytes))).Replace('-','').ToLowerInvariant()
} finally {
    $sha256.Dispose()
}
if ($CertificateSha256.Length -ne 64) { throw 'Invalid signing certificate SHA256 length.' }
Write-Host 'Uploading one stable signing identity to GitHub Actions...'
function Set-SecretFromStdin([string]$name,[string]$value) {
    $value | & gh secret set $name -R $Repo
    if ($LASTEXITCODE -ne 0) { throw "Could not create GitHub secret: $name" }
}
Set-SecretFromStdin 'SMART_ENERGY_ANDROID_KEYSTORE_BASE64' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($Store)))
Set-SecretFromStdin 'SMART_ENERGY_ANDROID_STORE_PASSWORD' $Pass
Set-SecretFromStdin 'SMART_ENERGY_ANDROID_KEY_ALIAS' $Alias
Set-SecretFromStdin 'SMART_ENERGY_ANDROID_KEY_PASSWORD' $Pass
Set-SecretFromStdin 'SMART_ENERGY_ANDROID_CERT_SHA256' $CertificateSha256
Write-Host 'Permanent signing configured for GitHub Actions.'
Write-Host ('Signing key location: '+$Store)
Write-Host 'MAKE AN OFFLINE BACKUP of both key and recovery file. Keep them private.'
Write-Host 'Do not uninstall your old debug APK; the new official app is separate.'
