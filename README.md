# RongMC 客户端与实时导览

简体中文 · [English](./README.en.md)

在游戏内播放实时运镜导览。相机按关键帧沿真实世界移动，玩家可在界面暂停、跳过或完成导览。仓库还包含整合包首次设备设置的实现。

## 下载与安装

- 版本 · [v0.4.0-dev+26.3](https://github.com/OpenRongMC/RongMC-Client/releases/tag/v0.4.0-dev%2B26.3) (预发布)
- 下载文件 · `rongmc-client-0.4.0-dev.26.3.jar`
- 运行环境 · Minecraft Java 26.3, Fabric, Java 25
- Fabric Loader · `>=0.18.4`
- Fabric 模组 ID · `rongmc_client`
- JAR 声明的依赖 · [Fabric API](https://modrinth.com/mod/fabric-api)

把发行 JAR 放入对应实例的 `mods/` 目录。只用首启配置时，客户端需要随整合包附带的可选模组清单。运行服务器导览时，客户端和服务端都装相容版本。GitHub 预发布与整合包内附构建版本可能不同，先核对发布标签。

## 如何验证

先用创意或旁观模式试录关键帧，按 F7 记录、F8 打开编辑器，也可用 /rongmctour editor。多人用 /rongmctour 测试，确认结束或跳过能恢复视角和位置。

## 首启设备设置

RongMC 低配包从超低配开始，选择更高档位可在重启时加入可选模组。含触摸控制选项的较新整合包构建在启用触摸时会跳过 Spatial GUI。

## 从源码开始

```sh
./gradlew build
```

更改导览需在隔离客户端与服务端验证。

## 文档与反馈

- [运镜编辑器手册](./docs/camera-editor.md) · [服务端配置示例](./examples/rongmc-tour-server.json)
- [原 README（旧版指南、贡献者与署名）](https://github.com/OpenRongMC/RongMC-Client/blob/d18a93fcf19a2670580aeb30fcfafddca068876a/README.md)
- [报告 RongMC 移植问题](https://github.com/OpenRongMC/RongMC-Client/issues) · 请附游戏版本、两端 JAR 文件名、复现步骤和相关日志。

## 许可与署名

请参阅本仓库的 [LICENSE](./LICENSE) 及原项目的许可、署名和第三方资源说明。
