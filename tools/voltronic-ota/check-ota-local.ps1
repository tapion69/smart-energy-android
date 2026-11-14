# Read-only OTA compatibility check for a local Smart Energy Voltronic ESP32-S3.
# Does not upload a firmware, change inverter settings, or print the access code.
param(
    [Parameter(Mandatory=$false)][string]$Ip,
    [int]$Port=18081
)
$ErrorActionPreference='Stop'
if ([string]::IsNullOrWhiteSpace($Ip)) { $Ip=Read-Host 'Adresse IP locale du lecteur Voltronic S3' }
$parsed=$null
if (-not [Net.IPAddress]::TryParse($Ip,[ref]$parsed) -or
    $parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
    throw 'Adresse IPv4 invalide.'
}
$b=$parsed.GetAddressBytes()
if (-not (($b[0] -eq 10) -or
          ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31) -or
          ($b[0] -eq 192 -and $b[1] -eq 168))) {
    throw 'Seule une adresse IP privée du réseau local est acceptée.'
}
if ($Port -lt 1 -or $Port -gt 65535) { throw 'Port invalide.' }
$secure=Read-Host 'Code ESP32 Voltronic (pas le code VPN)' -AsSecureString
$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $secret=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($p)
    if ($secret.Length -lt 8 -or $secret.Length -gt 32) { throw 'Code attendu : 8 à 32 caractères.' }
    $headers=@{'X-Setup-Code'=$secret}
    $base="http://${Ip}:${Port}"
    try {
        $info=Invoke-RestMethod -Uri "$base/api/v1/info" -Headers $headers -TimeoutSec 8 -MaximumRedirection 0
        if ($info.source -ne 'voltronic-rs232-read-only') { throw 'Réponse non Voltronic.' }
        $status=Invoke-RestMethod -Uri "$base/api/v1/ota/status" -Headers $headers -TimeoutSec 8 -MaximumRedirection 0
        if ($status.source -ne 'voltronic-ota-status' -or $status.target -ne 'esp32s3-n16r8') {
            throw 'Endpoint OTA inconnu ou incompatibilité de matériel.'
        }
        Write-Host ('Firmware installé : '+$info.version)
        Write-Host ('Partitionnement : '+$status.partition_scheme)
        Write-Host ('Compatible OTA directe : '+$status.ota_supported)
        if ($status.PSObject.Properties.Name -contains 'running_partition') {
            Write-Host ('Partition active : '+$status.running_partition)
        }
        if ($status.PSObject.Properties.Name -contains 'rollback_pending') {
            Write-Host ('Rollback en attente : '+$status.rollback_pending)
        }
        if ($status.ota_supported -eq $true -and $status.rollback_pending -ne $true) {
            Write-Host 'Prêt pour un essai OTA local après publication et vérification SHA-256.'
        } else {
            Write-Host 'Ne PAS lancer une OTA. Une migration USB ou une validation de redémarrage est nécessaire.'
        }
    } catch {
        Write-Host ('Lecture OTA refusée ou inaccessible : '+$_.Exception.GetType().Name)
        throw 'Aucune mise à jour effectuée ; vérifier IP, Wi-Fi local, code et firmware.'
    }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)
    Remove-Variable secret,headers -ErrorAction SilentlyContinue
}
