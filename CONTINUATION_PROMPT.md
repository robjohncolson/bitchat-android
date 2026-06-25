# Continuation Prompt: Bitchat Android Dogecoin Testnet Wallet

Continue work in:

```text
C:\Users\rober\Downloads\Projects\bitchat-android
```

Goal: continue the Dogecoin testnet wallet integration in Bitchat Android from the current state. Work autonomously, inspect the relevant files first, keep changes focused, do not revert unrelated user changes, and verify with the most relevant Gradle/emulator checks.

## Current State

- The app now has a Dogecoin testnet wallet milestone built into Bitchat.
- The implementation is intentionally testnet-only.
- The wallet creates and stores a local secp256k1 Dogecoin testnet key.
- The app can show a receive address, generate/copy/share `dogecoin:` payment URIs, query a local Dogecoin Core testnet RPC node, list UTXOs, locally sign legacy P2PKH transactions, and broadcast raw transactions.
- No custodial wallet service, mainnet support, or remote key storage has been added.

Important current receive address in the emulator:

```text
nW9N5qAcbXnUBNmGRsRAj8iwPz31aFfCy8
```

This address was copied to the Windows clipboard during the previous session.

## Local Dogecoin Node

Dogecoin Core is running locally in testnet mode:

```text
"C:\Program Files\Dogecoin\dogecoin-qt.exe" -testnet
```

RPC is available on the host:

```text
http://127.0.0.1:44555/
```

The emulator reaches the host RPC through:

```text
http://10.0.2.2:44555
```

RPC credentials are in:

```text
C:\Users\rober\AppData\Roaming\Dogecoin\dogecoin.conf
```

Do not print the RPC password in user-facing output. It is fine to read the config locally for RPC calls when needed.

Last known node state:

- Chain: `test`
- Wallet balance in Dogecoin Core: `0`
- Node was still in initial block download.
- Blocks were far behind headers, so mining before sync produced a stale block.

The user ran this command in Dogecoin-Qt debug console:

```text
generatetoaddress 1 "nW9N5qAcbXnUBNmGRsRAj8iwPz31aFfCy8"
```

It returned:

```text
[
  "241989896695a3c4a2573c87d5d55591910b1a9aacfd279352f1478210c67196"
]
```

RPC inspection showed that block paid 10000 testnet DOGE to the Bitchat address, but it had:

```text
confirmations: -1
```

So it is stale/orphaned and not spendable. The next funding attempt should wait until the node finishes syncing.

Once Dogecoin Core is synced, mine mature testnet funds with:

```text
generatetoaddress 31 "nW9N5qAcbXnUBNmGRsRAj8iwPz31aFfCy8" 10000000
```

Then refresh the wallet balance in Bitchat.

## Android/Emulator State

Android SDK:

```text
C:\Users\rober\AppData\Local\Android\Sdk
```

JDK:

```text
C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot
```

AVD:

```text
bitchat_api35
```

Device:

```text
emulator-5554
```

Debug APK:

```text
app\build\outputs\apk\debug\app-universal-debug.apk
```

Useful setup in PowerShell:

```powershell
$env:ANDROID_HOME='C:\Users\rober\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:Path="$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\emulator;$env:Path"
```

Useful emulator commands:

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-universal-debug.apk
adb shell monkey -p com.bitchat.droid -c android.intent.category.LAUNCHER 1
```

Permissions previously granted with:

```powershell
$pkg='com.bitchat.droid'
$perms=@(
 'android.permission.BLUETOOTH_ADVERTISE','android.permission.BLUETOOTH_CONNECT','android.permission.BLUETOOTH_SCAN',
 'android.permission.ACCESS_COARSE_LOCATION','android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_BACKGROUND_LOCATION',
 'android.permission.NEARBY_WIFI_DEVICES','android.permission.POST_NOTIFICATIONS','android.permission.RECORD_AUDIO','android.permission.CAMERA',
 'android.permission.READ_MEDIA_IMAGES','android.permission.READ_MEDIA_VIDEO','android.permission.READ_MEDIA_AUDIO'
)
foreach ($perm in $perms) { adb shell pm grant $pkg $perm 2>$null | Out-Null }
adb shell dumpsys deviceidle whitelist +$pkg
```

## Files To Inspect First

Dogecoin wallet:

```text
app/src/main/java/com/bitchat/android/features/dogecoin/DogecoinWallet.kt
app/src/main/java/com/bitchat/android/features/dogecoin/DogecoinTransaction.kt
app/src/main/java/com/bitchat/android/features/dogecoin/DogecoinWalletRepository.kt
app/src/main/java/com/bitchat/android/features/dogecoin/DogecoinRpcClient.kt
app/src/main/java/com/bitchat/android/features/dogecoin/DogecoinWalletSheet.kt
```

UI integration:

```text
app/src/main/java/com/bitchat/android/ui/AboutSheet.kt
app/src/main/java/com/bitchat/android/ui/ChatScreen.kt
app/src/main/java/com/bitchat/android/ui/DogecoinUri.kt
app/src/main/java/com/bitchat/android/ui/MessageSpecialParser.kt
app/src/main/java/com/bitchat/android/ui/MessageComponents.kt
app/src/main/res/values/strings.xml
app/src/main/AndroidManifest.xml
app/src/main/res/xml/network_security_config.xml
```

Tests:

```text
app/src/test/java/com/bitchat/android/features/dogecoin/DogecoinWalletTest.kt
app/src/test/java/com/bitchat/android/ui/DogecoinUriTest.kt
app/src/test/java/com/bitchat/android/ui/MessageSpecialParserTest.kt
```

## Verification Already Run

These previously passed:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.bitchat.android.features.dogecoin.DogecoinWalletTest"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
git diff --check
```

Emulator smoke tests previously showed:

- App launched as `com.bitchat.droid/com.bitchat.android.MainActivity`.
- Dogecoin wallet sheet opened from About/Settings.
- RPC config worked against the local testnet node after credentials were entered.
- Balance refresh worked but showed `0 DOGE` because no spendable UTXOs existed.
- Cleartext HTTP to `10.0.2.2` was fixed through `network_security_config.xml`.

## Suggested Next Steps

1. Check Dogecoin Core sync status.
2. If still syncing, tell the user to wait or continue with non-funding wallet improvements.
3. If synced, mine mature testnet funds to the Bitchat address with `generatetoaddress 31 ...`.
4. Refresh Bitchat wallet balance and confirm UTXOs appear.
5. Test sending a small transaction from Bitchat back to another testnet address.
6. If making code changes, run focused unit tests first, then `testDebugUnitTest`, `assembleDebug`, and emulator smoke checks as appropriate.

## Constraints

- Do not add mainnet wallet support unless the user explicitly asks.
- Do not add custodial signing, remote key storage, or seed export flows without explicit approval.
- Do not print private keys or RPC passwords in the final answer.
- Do not use destructive git commands.
- The worktree has many unrelated changes. Do not revert unrelated files.
- Follow the existing Kotlin/Compose style and keep changes narrowly scoped.
