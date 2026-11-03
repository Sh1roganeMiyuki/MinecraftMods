# Spear Mod

长枪武器及相关附魔。Minecraft 1.21.1 + Fabric。

**当前只有骨架，功能待实现。**

## 构建

在**仓库根**执行（不是在 mod 目录里，本模块没有自己的 gradlew）：

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew :spearmod:build
```

产物在 `build/libs/spearmod-<version>.jar`，不要用带 `-sources` 的那个。

## 开发运行

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew :spearmod:runClient
```

## 联机

服务端和每个客户端都需要安装 Fabric Loader、Fabric API 和本模组，版本保持一致。
