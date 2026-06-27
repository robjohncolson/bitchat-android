# Smart Dogecoin SPV checkpoint generator (no full header sync).
#
# bitcoinj's stock BuildCheckpoints header-syncs the WHOLE chain to compute cumulative chainwork — on
# Dogecoin testnet (~65.7M blocks) that takes hours. Instead, the node already KNOWS the chainwork at every
# block (getblockheader returns it), so we build the bitcoinj StoredBlock "compact" records DIRECTLY from RPC
# (12-byte chainwork + 4-byte height + 80-byte header) and emit the CheckpointManager text format. The
# DogecoinSpvService loads the resulting asset to seed the SPVBlockStore near a key's birthdate -> fast sync.
#
# bitcoinj 0.14.7 stores chainwork in 12 bytes (CHAIN_WORK_BYTES=12); the script FAILS if a block's chainwork
# exceeds that (a real limit for mainnet eventually; testnet is well under it).
#
# Usage:  ./gen-dogecoin-checkpoint.ps1 -Network testnet -DepthsFromTip 50000,1000000,10000000 -OutFile <path>
#
# NOTE: this logic is correct and was used to generate the committed assets (hash-verified against the node
# via the spv-spike `verifyCheckpoint` mode). On some Windows setups, invoking it via `& script.ps1` makes
# dogecoin-cli intermittently report "couldn't connect to server: EOF reached" while the SAME calls succeed
# when the body is run inline/dot-sourced in the session. If you hit that, dot-source it (`. ./gen-...ps1`)
# or paste the body into your shell. The byte format below is the authoritative reference regardless.
param(
    [string]$Network = "testnet",
    [int[]]$DepthsFromTip = @(50000),
    [string]$OutFile
)

$cli = "C:\Program Files\Dogecoin\daemon\dogecoin-cli.exe"
$netFlag = if ($Network -eq "mainnet") { @() } else { @("-$Network") }

# Retry: the testnet node mints blocks so fast its RPC frequently EOFs mid-call.
function DogeRpc([string[]]$rpcArgs) {
    for ($try = 1; $try -le 10; $try++) {
        $out = & $cli @netFlag @rpcArgs
        if ($LASTEXITCODE -eq 0 -and $out) { return $out }
        Start-Sleep -Milliseconds 800
    }
    throw "RPC failed after 10 tries: $($rpcArgs -join ' ')"
}

$tip = [int](DogeRpc @("getblockcount"))
Write-Output "[gen] $Network tip height = $tip"

$records = @()
foreach ($depth in ($DepthsFromTip | Sort-Object -Descending)) {
    $height = $tip - $depth
    if ($height -lt 1) { Write-Output "[gen] skip depth $depth (height < 1)"; continue }
    $hash = (DogeRpc @("getblockhash", "$height")).Trim()
    $headerHex = (DogeRpc @("getblockheader", $hash, "false")).Trim()           # raw 80-byte header
    $info = DogeRpc @("getblockheader", $hash, "true") | ConvertFrom-Json       # chainwork + fields
    $chainworkHex = $info.chainwork

    if ($headerHex.Length -ne 160) { throw "height ${height}: header is $($headerHex.Length/2) bytes, expected 80" }
    if ($chainworkHex.Length -ne 64) { throw "height ${height}: chainwork is $($chainworkHex.Length/2) bytes, expected 32" }
    if ($chainworkHex.Substring(0,40) -ne ('0'*40)) {
        throw "height ${height}: chainwork $chainworkHex exceeds bitcoinj 0.14.7's 12-byte limit (CHAIN_WORK_BYTES=12)"
    }
    $cw12Hex = $chainworkHex.Substring(40,24)            # low 12 bytes, big-endian
    $heightHex = '{0:x8}' -f $height                     # 4 bytes, big-endian (matches ByteBuffer.putInt)
    $recordHex = $cw12Hex + $heightHex + $headerHex      # 12 + 4 + 80 = 96-byte StoredBlock.serializeCompact

    $bytes = [byte[]]::new($recordHex.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [Convert]::ToByte($recordHex.Substring($i*2, 2), 16) }
    $b64 = [Convert]::ToBase64String($bytes)
    $records += $b64
    Write-Output "[gen]  checkpoint height=$height time=$(([DateTimeOffset]::FromUnixTimeSeconds($info.time)).UtcDateTime) chainwork(low12)=$cw12Hex"
}

if ($records.Count -eq 0) { throw "no checkpoints generated" }

# CheckpointManager text format: magic, #signatures (0), #checkpoints, then one base64 compact StoredBlock per line.
$lines = @("TXT CHECKPOINTS 1", "0", "$($records.Count)") + $records
$content = ($lines -join "`n") + "`n"
if (-not $OutFile) { $OutFile = Join-Path $PSScriptRoot "..\..\app\src\main\assets\dogecoin-checkpoints-$Network.txt" }
$dir = Split-Path -Parent $OutFile
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
[System.IO.File]::WriteAllText($OutFile, $content, [System.Text.Encoding]::ASCII)  # ASCII = no BOM, LF endings
Write-Output "[gen] wrote $($records.Count) checkpoint(s) -> $OutFile"
