param(
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin',
    [string]$MavenCommand = (Join-Path $PSScriptRoot '..\mvnw.cmd'),
    [string[]]$MavenArguments = @()
)

$ErrorActionPreference = 'Stop'
$serverExe = Join-Path $MySqlBin 'mysqld.exe'
$clientExe = Join-Path $MySqlBin 'mysql.exe'
$adminExe = Join-Path $MySqlBin 'mysqladmin.exe'
$dumpExe = Join-Path $MySqlBin 'mysqldump.exe'
foreach ($binary in @($serverExe, $clientExe, $adminExe, $dumpExe)) {
    if (-not (Test-Path -LiteralPath $binary)) { throw "Executável ausente: $binary" }
}
$version = & $serverExe --no-defaults --version
if ($version -notmatch 'Ver 8\.0\.') { throw 'Este script requer MySQL 8.0, não MariaDB.' }

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$taskDirectory = Join-Path $tempRoot ('beachcleanup-mysql-ci-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $taskDirectory
$dataDirectory = Join-Path $taskDirectory 'data'
$baseDirectory = Split-Path -Parent $MySqlBin
$serverProcess = $null
$savedEnvironment = @{}
foreach ($name in @('MYSQL_TEST_SERVER_URL', 'MYSQL_TEST_USER', 'MYSQL_TEST_PASSWORD', 'MYSQL_TEST_CLIENT_BIN')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $testPort = $listener.LocalEndpoint.Port
    $listener.Stop()
    $initArguments = @('--no-defaults', '--initialize-insecure', '--console',
        ('--basedir="{0}"' -f $baseDirectory), ('--datadir="{0}"' -f $dataDirectory))
    $initialization = Start-Process -FilePath $serverExe -ArgumentList $initArguments -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $taskDirectory 'init.stdout') `
        -RedirectStandardError (Join-Path $taskDirectory 'init.stderr') -Wait -PassThru
    if ($initialization.ExitCode -ne 0) {
        Get-Content -LiteralPath (Join-Path $taskDirectory 'init.stderr')
        throw 'Falha ao inicializar o MySQL temporário.'
    }
    $serverArguments = @('--no-defaults', '--no-monitor', ('--basedir="{0}"' -f $baseDirectory),
        ('--datadir="{0}"' -f $dataDirectory), "--port=$testPort", '--bind-address=127.0.0.1',
        '--mysqlx=0', '--skip-log-bin', '--innodb-buffer-pool-size=64M',
        ('--log-error="{0}"' -f (Join-Path $taskDirectory 'server.log')))
    $serverProcess = Start-Process -FilePath $serverExe -ArgumentList $serverArguments `
        -WindowStyle Hidden -PassThru
    $clientArguments = @('--no-defaults', '--protocol=tcp', '--host=127.0.0.1', "--port=$testPort", '--user=root')
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($serverProcess.HasExited) { throw 'O servidor MySQL temporário encerrou antes dos testes.' }
        $ping = Start-Process -FilePath $adminExe -ArgumentList ($clientArguments + @('ping', '--silent')) `
            -WindowStyle Hidden -Wait -PassThru `
            -RedirectStandardOutput (Join-Path $taskDirectory 'ping.stdout') `
            -RedirectStandardError (Join-Path $taskDirectory 'ping.stderr')
        if ($ping.ExitCode -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Tempo limite ao aguardar o MySQL temporário.' }
    & $clientExe @clientArguments --execute="ALTER USER 'root'@'localhost' IDENTIFIED BY 'ci-only-password'"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao configurar a conta descartável de testes.' }
    $env:MYSQL_TEST_SERVER_URL = "jdbc:mysql://127.0.0.1:$testPort/?allowPublicKeyRetrieval=true&useSSL=false"
    $env:MYSQL_TEST_USER = 'root'
    $env:MYSQL_TEST_PASSWORD = 'ci-only-password'
    $env:MYSQL_TEST_CLIENT_BIN = $MySqlBin
    Write-Host "MySQL temporário: $version; porta $testPort."
    Push-Location (Join-Path $PSScriptRoot '..')
    try {
        & $MavenCommand @MavenArguments -B -Pmysql-it verify
        if ($LASTEXITCODE -ne 0) { throw "A verificação Maven falhou (código $LASTEXITCODE)." }
        python tools/validate_openapi.py
        if ($LASTEXITCODE -ne 0) { throw 'A verificação OpenAPI falhou.' }
        python -m unittest discover -s tools -p 'test_*.py'
        if ($LASTEXITCODE -ne 0) { throw 'A conferência decimal da auditoria falhou.' }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    if ($null -ne $serverProcess -and -not $serverProcess.HasExited) {
        # Only stop the process started by this script, never a Windows MySQL service.
        $serverProcess.Kill()
        $serverProcess.WaitForExit()
    }
    $logDirectory = Join-Path $PSScriptRoot '..\target\mysql-test-logs'
    $null = New-Item -ItemType Directory -Path $logDirectory -Force
    foreach ($logName in @('init.stdout', 'init.stderr', 'server.log', 'ping.stderr')) {
        $logPath = Join-Path $taskDirectory $logName
        if (Test-Path -LiteralPath $logPath) { Copy-Item -LiteralPath $logPath -Destination $logDirectory -Force }
    }
    # Verify the exact generated target before any recursive deletion.
    $resolvedTarget = (Get-Item -LiteralPath $taskDirectory).FullName
    if ([IO.Path]::GetDirectoryName($resolvedTarget) -ne $tempRoot -or
            [IO.Path]::GetFileName($resolvedTarget) -notmatch '^beachcleanup-mysql-ci-[a-f0-9]{32}$') {
        throw 'Diretório temporário fora do local esperado; limpeza recusada.'
    }
    Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
}
