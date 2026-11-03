# MinecraftMods

Minecraft 1.21.1 + Fabric 小玩法模组的 **Gradle 多模块 monorepo**。
公共构建配置只有一份在根目录，各 mod 里只有自己的代码。

## 现有的 mod

| 模块 | 说明 | 状态 |
|---|---|---|
| [funmod](funmod/) | 门扇挥扫冲击等小玩法 | 门挥扫已实现，游戏内待验证 |
| [spearmod](spearmod/) | 长枪武器与相关附魔 | 只有骨架 |

## 新建一个 mod

```powershell
.\tools\new-mod.ps1 -ModId gunmod -ClassName GunMod -Description "某种枪"
```

生成 8 个文件，然后就能构建：

```powershell
./gradlew :gunmod:build
```

**不需要任何登记动作**——`settings.gradle` 自动发现带 `build.gradle` 的一级子目录，CI 也一样。

## 构建

一律在**仓库根**执行，各 mod 目录里没有 gradlew：

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"

./gradlew build                 # 所有 mod
./gradlew :funmod:build         # 只构建一个
./gradlew :funmod:runClient     # 开发客户端
./gradlew projects              # 看当前发现了哪些子模块（3 秒）
```

产物在 `<mod>/build/libs/<mod>-0.1.0.jar`，不要用带 `-sources` 的那个。

## 文档

- **[AGENTS.md](AGENTS.md)** —— 仓库级说明：架构、新建 mod 的完整流程、构建命令、
  7 个已踩过的坑、环境事实、升级版本该改哪里
- 各 mod 还有自己的 `AGENTS.md`，只记该模块特有的东西

## License

CC0-1.0
