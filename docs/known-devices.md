# Known Android Test Devices

The app can only read normal Android runtime build fields (`android.os.Build.*`). ADB serials are documented for developer reference but should not be used inside app logic.

| ADB serial | UI name | Manufacturer | Brand | Model | Device | Product name | Board | Hardware | Android | SDK | Build incremental | Runtime preset |
|---|---|---|---|---|---|---|---|---|---:|---:|---|---|
| `31071FDH2008FK` | Google Pixel 7 | Google | google | Pixel 7 | panther | panther | panther | panther | 16 | 36 | 15001963 | Follower/default |
| `DMIFHU7HUG9PKVVK` | OnePlus CPH2399 | OnePlus | OnePlus | CPH2399 | OP557AL1 | CPH2399EEA | k6893v1_64_k419 | mt6893 | 14 | 34 | S.2356111-1 | Controller |
| `29fec8f8` | Xiaomi 23021RAA2Y | Xiaomi | Redmi | 23021RAA2Y | topaz | topaz_eea | bengal | qcom | 15 | 35 | OS2.0.209.0.VMGEUXM | Follower/default |
| `4c637b9e` | Xiaomi 2410CRP4CG | Xiaomi | Xiaomi | 2410CRP4CG | uke | uke_eea | uke | qcom | 16 | 36 | OS3.0.3.0.WOZEUXM | Follower/default |

## App-side matching rules

Controller preset:

```kotlin
manufacturer.equals("OnePlus", ignoreCase = true) &&
model.equals("CPH2399", ignoreCase = true) &&
device.equals("OP557AL1", ignoreCase = true)
```

This is intentionally based on `Build.MANUFACTURER`, `Build.MODEL`, and `Build.DEVICE`, because those values are available to the app at runtime without privileged APIs.

## Extract command used

```powershell
adb devices
adb -s <serial> shell getprop ro.product.manufacturer
adb -s <serial> shell getprop ro.product.brand
adb -s <serial> shell getprop ro.product.model
adb -s <serial> shell getprop ro.product.device
adb -s <serial> shell getprop ro.product.name
adb -s <serial> shell getprop ro.product.board
adb -s <serial> shell getprop ro.hardware
adb -s <serial> shell getprop ro.build.version.release
adb -s <serial> shell getprop ro.build.version.sdk
adb -s <serial> shell getprop ro.build.version.incremental
```
