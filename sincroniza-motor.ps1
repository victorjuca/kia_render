# Copia a kia-render el motor de render de Karaoke Studio desde kia (los archivos de motor.txt)
# y los recursos (fondos y sin_audio.mp3). Ejecutalo despues de cambiar el render en kia.
#   .\sincroniza-motor.ps1
#   .\sincroniza-motor.ps1 -Kia "D:\otra\ruta\kia"
param([string]$Kia = "$env:USERPROFILE\Documents\NetBeansProjects\avida\v1\kia")

$ErrorActionPreference = "Stop"
$origen = Join-Path $Kia "src\kia\claude"
$destino = Join-Path $PSScriptRoot "src\main\java\kia\claude"
if (-not (Test-Path $origen)) { throw "No existe $origen (indica la carpeta de kia con -Kia)" }

Get-Content (Join-Path $PSScriptRoot "motor.txt") | Where-Object { $_ -and -not $_.StartsWith("#") } | ForEach-Object {
    $o = Join-Path $origen $_.Trim()
    if (Test-Path $o -PathType Container) {
        Copy-Item $o $destino -Recurse -Force
    } else {
        Copy-Item $o $destino -Force
    }
    Write-Host "motor: $_"
}

$recursos = Join-Path $PSScriptRoot "recursos"
New-Item -ItemType Directory -Force $recursos | Out-Null
foreach ($r in "Fondo Karaoke IA.jpg", "Fondo Karaoke IA_sin_logo.jpg", "sin_audio.mp3") {
    Copy-Item (Join-Path $Kia "src\kia\source\$r") $recursos -Force
    Write-Host "recurso: $r"
}
Write-Host "Listo. Compila con: mvn -DskipTests package"
