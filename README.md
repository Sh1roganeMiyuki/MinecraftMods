# MinecraftMods

Minecraft Fabric 小玩法模组的 monorepo。每个一级子目录是一个**完全独立的 Gradle 项目**，
可以单独构建、单独分发。

## 现有的 mod

| 目录 | 说明 |
|---|---|
| [funmod](funmod/) | 门扇挥扫冲击等小玩法 |

## 新建一个 mod

```powershell
.\tools\new-mod.ps1 -ModId spearmod -Description "长枪武器与相关附魔"
```

不需要任何登记动作，CI 会自动发现新项目。

## 构建

每个 mod 独立构建。本机没有系统 JDK，要先指定：

```powershell
cd funmod
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"; ./gradlew build
```

产物在 `<mod>/build/libs/<mod>-0.1.0.jar`，不要用带 `-sources` 的那个。

## 开发约定

多 agent 并行规则、全局缓存说明、已知的坑，都在 [AGENTS.md](AGENTS.md)。
每个 mod 还有自己的 `AGENTS.md`，进目录前先读。

## License

CC0-1.0
