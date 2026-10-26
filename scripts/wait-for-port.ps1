[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int] $Port,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 600)]
    [int] $TimeoutSeconds
)

$ErrorActionPreference = "Stop"
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)

do {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync([System.Net.IPAddress]::Loopback, $Port)
        if ($connect.Wait(250) -and $client.Connected) {
            return
        }
    }
    catch {
        # The service may still be starting; retry until the deadline.
    }
    finally {
        $client.Dispose()
    }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)

throw "Port $Port did not become ready within $TimeoutSeconds seconds."
