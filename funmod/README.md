# Fun Mod

Minecraft 1.21.1 + Fabric 的小玩法模组。

## 玩法

### 门扇挥扫

开/关任意原版木门、铁门时，门扇会像刚体一样扫出一片扇形冲击，把附近的实体（玩家、怪物、动物、掉落物、点燃的 TNT、盔甲架等）拍飞，伴随钝击音效和粒子。

- 玩家右键、红石信号、村民开门三种触发方式都生效
- 冲力方向沿门扇旋转的切线，离门轴越远飞得越远
- 观察者模式玩家和骑乘中的实体不受影响
- 参数可调：`config/funmod/door-slam.json`（首次启动自动生成）

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