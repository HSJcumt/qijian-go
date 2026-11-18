# 棋间围棋

棋间围棋是一款用 Java 17 编写的 Windows 围棋桌面游戏。玩家可以创建房间、邀请对手、观战和回放棋谱。Swing 客户端通过 TCP 连接游戏服务端。人机对局由服务端调用 KataGo，玩家电脑只负责显示棋盘和提交操作。

![游戏首页插画](src/go/client/assets/home-visual.png)

## 在本机试玩

准备 Windows、PowerShell 和 JDK 17 或更高版本。将 JAVA_HOME 指向 JDK 安装目录，或将其 bin 目录加入 PATH。在项目根目录打开 PowerShell，执行：

~~~powershell
.\build.ps1 -Test
.\start-local.ps1
~~~

第一条命令编译并运行回归测试，第二条启动本机服务端和客户端。用昵称进入大厅。再次运行第二条命令可打开另一位玩家的客户端。结束后关闭客户端并运行 `停止本机测试服务.bat`。

## 局域网对弈

在服务端电脑上运行 `启动服务器.bat`，并允许局域网客户端访问 TCP 9528。在玩家电脑上运行 `启动游戏.bat`，在入口页的“配置服务器地址”中填入服务端的局域网 IPv4 地址和端口 9528。默认地址 127.0.0.1 用于同机试玩。

## 安装人机对弈模型

在有 OpenCL 设备和相应驱动的 Windows x64 服务端上运行 `安装KataGo模型.bat`。脚本从上游下载引擎和权重，首次推理需要时间完成调优。真人对弈可直接运行。

## 构建分发包

~~~powershell
.\build.ps1 -Test
.\package.ps1 -Side all
Get-FileHash .\dist\packages\*.zip -Algorithm SHA256
~~~

输出位于 `dist/packages/`。玩家下载客户端 ZIP，部署者下载服务端 ZIP。默认包使用接收方安装的 Java 17+。若要预设服务器地址，打包时使用 `-ServerHost` 指定实际 IP 或域名。若要附带模型，先安装模型，再为打包命令添加 `-IncludeModels`。

## 项目目录

| 目录 | 内容 |
| --- | --- |
| src/go/core、src/go/net | 围棋规则、棋谱与网络协议 |
| src/go/client、src/go/server | 桌面界面与游戏服务端 |
| src/go/ai、src/go/model | 人机难度、引擎适配与模型任务 |
| test、samples | 回归测试与示例棋谱 |
| config、katago | 服务地址和引擎配置 |

KataGo 引擎与网络权重的第三方声明见 [引擎许可](katago/ENGINE-LICENSE.txt) 和 [权重说明](katago/NETWORK-LICENSE.html)。
