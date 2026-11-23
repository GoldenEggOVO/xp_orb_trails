# 安装 XP Orb Trails 效果包

[English](README.md)

| 下载 | 包含内容 |
| --- | --- |
| [2.1.0 效果收藏 ZIP](2.1.0example/2.1.0example.zip?raw=1) | 24 套可选方案，涵盖经验球本体、尾迹和拾取效果。[查看每套方案的触发条件](2.1.0example/README.zh-CN.md)。 |
| [2.0.0 图集示例 ZIP](2.0.0example/2.0.0example.zip?raw=1) | 锦鲤、灯笼、黄铜齿轮和飞船四套图集。[查看素材和预览动图](2.0.0example/)。 |

每个版本目录都有一个同名 ZIP。要编辑方案或素材，可以直接浏览 [2.0.0 的 pack 目录](2.0.0example/pack/) 或 [2.1.0 的 pack 目录](2.1.0example/pack/)。

## 在 2.1.0 中使用 ZIP

1. 从上表下载效果包。
2. 在模组设置中打开 **方案 → 打开方案包文件夹**，把整个 ZIP 放进去，保留压缩格式。这个目录位于当前游戏实例的 `config/xp_orb_trails/packs/`。
3. 在同一页打开 **文件方案 → 重新加载文件**，点选想用的方案。来自压缩包的方案名旁边会显示 ZIP 文件名。
4. 在世界里拾取几颗经验球。有些效果需要两颗球交汇、流动的水或通电的方块，可以查阅效果收藏里的触发说明。

更新时用同名 ZIP 替换旧包，重新加载文件并再次应用方案。卸载时先切换到其他方案，再移走 ZIP 并重新加载文件。

如果之前装过旧文件名的示例包，换用新 ZIP 时请移走旧副本，再点「重新加载文件」并重新选择方案。

**使用 2.0.0 时**，请下载图集示例，解压后将其中的 `profiles/`、`orb/`、`trail/` 和 `pickup/` 合并到 `config/xp_orb_trails/`。在设置里重新加载文件，再点选方案。

## 分享自己的方案

把 `profiles/` 和用到的素材目录放在 ZIP 的根目录。例如，`profiles/river_lights.json` 可以用 `sprite:orb/koi` 引用 `orb/sprites/koi/sheet.png`。把 JSON 和对应图片一起打包，其他玩家就能直接安装。[中文玩家 Wiki](https://goldeneggovo.github.io/xp_orb_trails/wiki/zh-cn.html#test-and-share-your-look)
