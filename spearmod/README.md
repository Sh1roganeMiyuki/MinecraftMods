# Spear Mod

Spear Mod —— Minecraft Fabric 小玩法模组。

## 构建

本机没有系统 Java，先指定已安装的 JDK：

```bash
export JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew build
```

产物在 `build/libs/spearmod-<version>.jar`（不要用带 `-sources` 的那个）。

## 开发运行

```bash
export JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew runClient
```

## 联机

服务端和每个客户端都需要安装 Fabric Loader、Fabric API 和本模组，版本保持一致。