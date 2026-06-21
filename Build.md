# 编译Debug


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


# 编译release

先切到项目根目录，然后在 PowerShell 里执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleRelease
```

如果你只想编某一个 release 风味，用下面这些：

```powershell
.\gradlew.bat :app:assembleOssRelease
.\gradlew.bat :app:assembleFdroidRelease
.\gradlew.bat :app:assemblePlayRelease
.\gradlew.bat :app:assemblePreviewRelease
```

说明一下这次结果：

- `:app:assembleRelease` 会一次性编出所有 release 变体
- 编译成功
- 工作区现在是干净的
- 过程中有不少 warning，但没有阻塞 release 产出

如果你要，我下一步可以继续帮你做两件事之一：

- 只保留一个你要发的 release 风味，帮你定位最终该发哪一个
- 再补一条“自动 zipalign / apksigner 验签检查”的命令给你