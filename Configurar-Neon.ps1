# Assistente: guarda o endereço do banco (Neon) para o servidor do Apex usar.
# O arquivo fica na SUA pasta de usuário, fora do projeto, e nunca vai para o git.
$ErrorActionPreference = 'Stop'
$dir = Join-Path $env:USERPROFILE '.apex-server'
$file = Join-Path $dir '.env'
New-Item -ItemType Directory -Force $dir | Out-Null

Write-Host ''
Write-Host 'Cole o endereço de conexao do Neon (Connection string) e aperte Enter.' -ForegroundColor Cyan
Write-Host 'Ele comeca com postgresql://  (no painel: Connect > desligue "Connection pooling" > copie).'
Write-Host ''
$url = (Read-Host 'Connection string').Trim().Trim('"')

if ($url -notmatch '^postgres(ql)?://') {
    Write-Host 'Isso nao parece um endereco postgresql://. Nada foi salvo.' -ForegroundColor Red
    exit 1
}
if ($url -match '-pooler\.') {
    Write-Host 'Atencao: este endereco e o "pooled" (tem -pooler no nome). Prefira o direto: desligue "Connection pooling" no painel.' -ForegroundColor Yellow
}

# Segredo que assina os logins: gerado uma vez e reaproveitado.
$secret = $null
if (Test-Path $file) {
    $old = Get-Content $file | Where-Object { $_ -match '^JWT_SECRET=' } | Select-Object -First 1
    if ($old) { $secret = $old -replace '^JWT_SECRET=', '' }
}
if (-not $secret) {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

$lines = @(
    '# Configuracao do servidor do Apex (nao compartilhe este arquivo).',
    "DATABASE_URL=$url",
    "JWT_SECRET=$secret"
)
[System.IO.File]::WriteAllLines($file, $lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host "Pronto! Salvei em: $file" -ForegroundColor Green
Write-Host 'Agora abra o Servidor.cmd. Se tudo deu certo, ele NAO mostra o aviso "Modo desenvolvimento".'
