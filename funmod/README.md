# Fun Mod

Minecraft 1.21.1 + Fabric 的小玩法模组。

## 玩法

### 门扇挥扫

开/关任意原版木门、铁门时，门扇会像刚体一样扫出一片扇形冲击，把附近的实体（玩家、怪物、动物、掉落物、点燃的 TNT、盔甲架等）拍飞，伴随钝击音效和粒子。

- 玩家右键、红石信号、村民开门三种触发方式都生效
- 击飞方向有四种算法可选（默认 NORMAL：从门板所在的那条边推向方块外）
- 离门轴越远飞得越远；多扇门同时拍会叠加，但有速度上限
- 观察者模式玩家和骑乘中的实体不受影响

### 烈焰弹投掷

手持烈焰弹右键（对空或对方块都行）直接掷出**恶魂大火球**，而不是原版的原地摩擦点火。

- 完整的原版火球行为：爆炸点火、小范围击退、被剑打一下能反弹并转移归属
- 击杀计入投掷者名下
- 默认 1 秒发射冷却，防连点器刷屏
- 创造模式不消耗烈焰弹
- 箱子、工作台、门这些照样能开——只有本来会点火的那一下改成投掷

## 参数调节

游戏里「模组列表 → Fun Mod → 配置」（需要装 [Mod Menu](https://modrinth.com/mod/modmenu)）。
拖滑条、点开关，按完成就存盘并**立即生效**，不需重启也不需 reload。

磁盘上落在 `config/funmod.json`，也可以直接改文件（那样需要重启）。

## 构建

在**仓库根**执行（不是在 mod 目录里，本模块没有自己的 gradlew）：

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew :funmod:build
```

产物在 `funmod/build/libs/funmod-<version>.jar`，不要用带 `-sources` 的那个。

## 开发运行

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"
./gradlew :funmod:runClient
```

## 联机

服务端和每个客户端都需要安装 Fabric Loader、Fabric API 和本模组，版本保持一致。
MidnightLib 已经以 jar-in-jar 形式打进产物，不用单独装。Mod Menu 只影响配置界面，不装也能正常玩。