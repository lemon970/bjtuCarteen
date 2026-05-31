# 餐厅仿真系统 - 启动说明

## 一、首次准备 (一次性)

确认电脑已安装 **JDK 17 及以上**。

1. 打开 PowerShell 或 cmd,运行 `java -version`
2. 如果显示 `openjdk version "21.x.x"` 或 `"17.x.x"` 等,准备完毕
3. 如果提示"不是内部或外部命令",前往 https://adoptium.net/temurin/releases/ 下载 JDK 21 (Windows x64 .msi),安装时勾选 **Set JAVA_HOME variable** 和 **Add to PATH**

## 二、启动

直接 **双击 `run.bat`**。

启动器会自动:

1. 检查 Java 环境
2. 启动仿真后端服务 (新窗口)
3. 等待服务就绪 (一般 5-15 秒)
4. 自动打开浏览器到首页 http://localhost:8080/

启动成功后会有两个窗口:

- **启动器窗口** (本窗口) — 等你按键停止
- **后端窗口** (标题 `bjtu-canteen-simulator-backend`) — 显示服务日志,**不要手动关闭**

## 三、停止

在启动器窗口按任意键。后端会自动关闭,8080 端口释放。

## 常见错误

| 错误信息 | 处置 |
|---|---|
| `未检测到 Java 运行时` | 按"首次准备"安装 JDK 17+ |
| `Java 版本 X 过低` | 升级 JDK 到 17 及以上 |
| `未找到 target\simulation-*-exec.jar` | 联系开发者获取构建好的版本,或开发者运行 `build.bat` |
| `8080 端口已被占用` | 关闭已在运行的实例,或执行 `netstat -ano \| findstr :8080` 找占用进程 |
| `后端启动超时` | 查看后端窗口红字日志。常见为 jar 损坏 / 资源文件缺失 |

## 兜底关停

如果启动器异常退出,后端窗口未关闭,在 PowerShell 运行:

```powershell
taskkill /IM java.exe /F
```

## 系统要求

- Windows 10 (1803+) 或 Windows 11
- JDK 17 及以上 (推荐 JDK 21)
- 默认浏览器 (Chrome / Edge / Firefox 任一)
- 8080 端口空闲
