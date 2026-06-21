在项目根目录的 PowerShell 里这样跑就行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```

如果你只想构建某个风味，也可以用：

```powershell
.\gradlew.bat :app:assembleOssDebug
.\gradlew.bat :app:assembleFdroidDebug
.\gradlew.bat :app:assemblePlayDebug
.\gradlew.bat :app:assemblePreviewDebug
```

构建产物一般在 `app/build/outputs/apk/` 下面。