# 餐厅仿真系统-启动说明

## 一、先确认拿到的版本

本项目现在支持不安装Maven运行。Java环境有两种情况：

| 获取方式 | 是否需要手动安装Java | 是否需要手动安装Maven | 说明 |
|---|---|---|---|
| 提交用完整源码包zip | 不需要 | 不需要 | 包内应带有`java\temurin-jdk17-windows-x64.zip`，首次运行会自动解压到`java\jdk\`。 |
| 从GitHub克隆源码 | 需要二选一 | 不需要 | GitHub仓库不提交JDK压缩包。可以安装JDK17+，也可以把`temurin-jdk17-windows-x64.zip`放到`java\`目录。 |

如果只是使用提交用完整源码包，解压后不要移动其中的`java`目录。

## 二、启动

直接 **双击 `run.bat`**。

启动器会自动:

1. 准备Java环境，优先使用`java\jdk\bin\java.exe`
2. 启动仿真后端服务 (新窗口)
3. 等待服务就绪 (一般5-15秒)
4. 自动打开浏览器到首页http://localhost:8080/

启动成功后会有两个窗口:

- **启动器窗口**(本窗口)：等你按键停止
- **后端窗口**(标题`bjtu-canteen-simulator-backend`)：显示服务日志，使用期间不要手动关闭

## 三、停止

在启动器窗口按任意键。后端会自动关闭，8080端口释放。

## 四、需要重新构建时

如果修改过源码，或提示找不到`target\simulation-*-exec.jar`，双击`build.bat`重新构建。

`build.bat`会自动调用`mvnw.cmd`，不要求电脑预装Maven。首次构建需要下载MavenWrapper所需文件、Node和前端依赖，联网情况下通常需要几分钟。

## 常见错误

| 错误信息 | 处置 |
|---|---|
| `Bundled JDK archive does not contain bin\java.exe` | JDK压缩包结构不符合要求。请使用完整源码包内的`java\temurin-jdk17-windows-x64.zip`，或安装JDK17+后重试。 |
| `Failed to prepare bundled JDK` | 可能是`java\jdk\`中的文件被占用。先关闭所有本系统窗口和残留`java.exe`进程，再重新运行。 |
| `未检测到Java运行时` | 提交用源码包请确认`java\temurin-jdk17-windows-x64.zip`存在；GitHub克隆版请安装JDK17+或手动放入JDK压缩包。 |
| `Java版本X过低` | 升级到JDK17及以上，或使用源码包内置JDK。 |
| `未找到target\simulation-*-exec.jar` | 双击`build.bat`重新构建。 |
| `8080端口已被占用` | 关闭已在运行的实例，或执行`netstat -ano \| findstr :8080`找占用进程。 |
| `后端启动超时` | 查看后端窗口红字日志。常见为jar损坏或资源文件缺失。 |

## 兜底关停

如果启动器异常退出，后端窗口未关闭，在PowerShell运行:

```powershell
taskkill /IM java.exe /F
```

## 系统要求

- Windows10(1803+)或Windows11
- 默认浏览器(Chrome、Edge、Firefox任一)
- 8080端口空闲
- 使用GitHub克隆版时，需要额外准备JDK17+或`java\temurin-jdk17-windows-x64.zip`
