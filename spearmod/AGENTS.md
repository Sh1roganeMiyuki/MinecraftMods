# Spear Mod 项目说明

仓库级的环境事实、构建命令、已知的坑都在 **`../AGENTS.md`**，动手前先读那个。
这里只记 spearmod 自己的东西。

## 项目目标

- 长枪武器及相关附魔
- 原本规划在 funmod 里，现在拆成独立子模块，可以单独装卸

## 本模块速查

- 模组 ID：`spearmod`；根包：`com.example.spearmod`
- 构建（在**仓库根**执行，本目录没有 gradlew）：`./gradlew :spearmod:build`
- 开发客户端：`./gradlew :spearmod:runClient`
- 产物：`spearmod/build/libs/spearmod-0.1.0.jar`

本机没有系统 JDK，PowerShell 里先设：

```powershell
$env:JAVA_HOME = "C:/Users/zhang/.jdks/jdk-25.0.5+8"
```

## 当前状态

**只有骨架，还没有任何功能代码。** `SpearMod.onInitialize` 里目前只有一行日志。

待办：

- 长枪物品本体（`SpearItem`）、材质、模型
- 相关附魔
- `assets/spearmod/icon.png` 现在是从 funmod 拷来的占位图，记得换
- `fabric.mod.json` 的 `description` 还是脚手架的默认文案

## 约定提醒

- 需要 spearmod 独有的依赖，写进本目录的 `build.gradle`（现在是空的），**不要改根配置**
- Mixin 清单已备好：`src/main/resources/spearmod.mixins.json`，类放 `com.example.spearmod.mixin`。
  refmap 由根 `build.gradle` 的 `useLegacyMixinAp = true` 保证生成，别在这里重复配置
- 伤害、击退逻辑必须在服务端执行；客户端只负责渲染、粒子、音效
- 资源路径与注册 ID 对齐：`assets/spearmod/...`、`data/spearmod/...`
