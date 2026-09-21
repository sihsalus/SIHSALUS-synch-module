param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('posta_a', 'posta_b')]
    [string]$ServerId,
    [switch]$SincronizarPacientes,
    [switch]$SincronizarEncuentros,
    [switch]$PrepararPacientesExistentes,
    [ValidateRange(1, 100)]
    [int]$TamanoLotePreparacion = 25
)

$ErrorActionPreference = 'Stop'
if ($SincronizarEncuentros -and -not $SincronizarPacientes) {
    throw 'Use -SincronizarPacientes junto con -SincronizarEncuentros para mantener disponibles los pacientes relacionados.'
}
$repoPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$trustPath = Join-Path $repoPath '.local-sync-https\truststore.p12'
if (-not (Test-Path -LiteralPath $trustPath)) {
    throw 'Falta el truststore local. Preparar HTTPS antes de arrancar la posta.'
}

# La comunicación de pacientes se habilita solo con el parámetro explícito.
$env:SYNCMR_ENABLED = 'false'
$env:SYNCMR_PREPARE_EXISTING_ENABLED = 'false'
$env:SYNCMR_PREPARE_ENCOUNTERS_ENABLED = 'false'
$env:SYNCMR_PREPARE_ORDERS_ENABLED = 'false'
Remove-Item Env:SYNCMR_MASTER_ENCOUNTER_ENDPOINT, Env:SYNCMR_MASTER_ORDER_ENDPOINT -ErrorAction SilentlyContinue
function Read-ConnectionPassword([string]$Prompt) {
    $secret = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try {
        $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if ([string]::IsNullOrWhiteSpace($value)) { throw 'La contraseña no puede estar vacía.' }
        return $value
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secret.Dispose()
    }
}
$javaTrustPath = $trustPath.Replace('\', '/')
$jvmArgs = '-Djavax.net.ssl.trustStore="' + $javaTrustPath + '" -Djavax.net.ssl.trustStorePassword=LocalTls_7392! -Djavax.net.ssl.trustStoreType=PKCS12'
Push-Location (Join-Path $repoPath 'synchronizationmr')
try {
    if ($SincronizarPacientes -or $PrepararPacientesExistentes) {
        $suffix = if ($ServerId -eq 'posta_a') { 'a' } else { 'b' }
        $env:SYNCMR_LOCAL_USERNAME = "sync_local_$suffix"
        $env:SYNCMR_LOCAL_PASSWORD = Read-ConnectionPassword "Contraseña de $env:SYNCMR_LOCAL_USERNAME en esta posta"
    }
    if ($PrepararPacientesExistentes) {
        $env:SYNCMR_PREPARE_EXISTING_ENABLED = 'true'
        $env:SYNCMR_PREPARE_BATCH_SIZE = [string]$TamanoLotePreparacion
        $env:SYNCMR_PREPARE_INTERVAL_SECONDS = '60'
        Write-Host "Preparacion de pacientes existentes habilitada: hasta $TamanoLotePreparacion por ciclo, cada 60 segundos."
    }
    if ($SincronizarPacientes) {
        $env:SYNCMR_REMOTE_USERNAME = "sync_posta_$suffix"
        $env:SYNCMR_REMOTE_PASSWORD = Read-ConnectionPassword "Contraseña de $env:SYNCMR_REMOTE_USERNAME en el maestro"
        $env:SYNCMR_MASTER_ENDPOINT = 'https://localhost:8443/openmrs/moduleServlet/synchronizationmr/patientSync'
        $env:SYNCMR_MASTER_SERVER_ID = 'microrred_maestro'
        $env:SYNCMR_INTERVAL_SECONDS = '60'
        $env:SYNCMR_ENABLED = 'true'
        Write-Host "Sincronización de pacientes habilitada para $ServerId cada 60 segundos."
    }
    if ($SincronizarEncuentros) {
        $env:SYNCMR_MASTER_ENCOUNTER_ENDPOINT = 'https://localhost:8443/openmrs/moduleServlet/synchronizationmr/encounterSync'
        Write-Host "Sincronizacion de encuentros habilitada para $ServerId. Preparacion historica de encuentros desactivada."
    }
    & mvn openmrs-sdk:run "-DserverId=$ServerId" "-DjvmArgs=$jvmArgs"
    if ($LASTEXITCODE -ne 0) { throw "El SDK terminó con código $LASTEXITCODE." }
} finally {
    $env:SYNCMR_ENABLED = 'false'
    $env:SYNCMR_PREPARE_EXISTING_ENABLED = 'false'
    Remove-Item Env:SYNCMR_MASTER_ENCOUNTER_ENDPOINT -ErrorAction SilentlyContinue
    Remove-Item Env:SYNCMR_PREPARE_BATCH_SIZE, Env:SYNCMR_PREPARE_INTERVAL_SECONDS -ErrorAction SilentlyContinue
    Remove-Item Env:SYNCMR_LOCAL_PASSWORD, Env:SYNCMR_REMOTE_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
