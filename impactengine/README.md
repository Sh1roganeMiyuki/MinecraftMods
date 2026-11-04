# Impact Engine

Impact Engine —— Minecraft Fabric 小玩法模组。

## 构建

在**仓库根**执行（不是在 mod 目录里，本模块没有自己的 gradlew）：

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew :impactengine:build
```

产物在 `build/libs/impactengine-<version>.jar`，不要用带 `-sources` 的那个。

## 开发运行

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew :impactengine:runClient
```