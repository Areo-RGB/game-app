$ErrorActionPreference = 'Stop'
$ProjectDir = 'C:\Users\paul\Documents\.projects\game-app'
Set-Location -LiteralPath $ProjectDir

git status --short
git add app/src/main/java/com/example/MainActivity.kt `
        app/src/main/java/com/example/MainViewModel.kt `
        app/src/main/java/com/example/data/SettingsEntity.kt `
        app/src/main/java/com/example/data/AppDatabase.kt

git commit -m "Add manual difficulty limit controls"
git push origin main

gh workflow run build-apk.yml --ref main
gh run list --workflow build-apk.yml --limit 5
