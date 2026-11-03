# Fun Mod

Minecraft 1.21.1 + Fabric 的小玩法模组。

## 构建

本机没有系统 Java，先指定已安装的 JDK 25：

```bash
export JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew build
```

产物在 `build/libs/funmod-<version>.jar`。

## 开发运行

```bash
export JAVA_HOME="C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew runClient
```

## 联机

服务端和每个客户端都需要安装 Fabric Loader、Fabric API 和本模组，版本保持一致。