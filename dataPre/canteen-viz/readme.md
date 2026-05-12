# 北京交通大学食堂就餐仿真系统 - 前端可视化模块

## 项目简介
本项目是《北京交通大学食堂就餐仿真系统》的前端可视化部分（成员B），负责接收后端仿真快照和统计数据，提供仿真回放看板和统计分析报告。

## 功能特性
- 仿真时间轴控制（拖拽/播放/暂停/重置）
- 实时展示当前时刻窗口排队长度、食堂占用率、人流量、平均等待时间
- 全局趋势图（占用率、总排队人数、人流量随时间变化）
- 场景切换对比（单独打包窗口 vs 不单独）
- 数据接收面板，支持模拟数据和正式 API 接收
- 统计分析报告页，展示仿真参数和关键拥挤指标

## 技术栈
- HTML5 / CSS3 / JavaScript (ES6 Modules)
- [ECharts 5.5.0](https://echarts.apache.org/) (CDN)
- [Tailwind CSS 2.2.19](https://tailwindcss.com/) (CDN)
- [Live Server](https://www.npmjs.com/package/live-server) (开发环境)

## 目录结构
```
canteen-viz/
├── index.html
├── css/main.css
├── js/
│   ├── data.js
│   ├── charts.js
│   ├── ui.js
│   └── main.js
├── package.json
└── README.md
```

## 运行说明
1. 确保已安装 [Node.js](https://nodejs.org/)。
2. 在项目根目录执行 `npm install` 安装开发依赖。
3. 执行 `npm start` 启动本地服务器，浏览器将自动打开 `http://localhost:3000`。
4. （可选）使用 VS Code 的 Live Server 插件或其他静态服务器运行 `index.html`。

## 数据接口
调用 `window.receiveSimulationData(json)` 可向页面注入仿真数据，数据格式需符合以下 JSON 结构：
```json
{
  "params": { /* 仿真参数 */ },
  "snapshots": [ /* 按分钟排序的快照数组 */ ],
  "trendData": { /* 趋势数据 */ },
  "peakOccupancy": 83,
  "peakMinute": 42,
  "crowdedMinutes": 23,
  "crowdedPercentage": 25.6
}
```
详细字段说明见完整文档或代码注释。

## 单元测试
针对核心函数编写了测试用例，见 `软件综合实训_[学号]_[姓名]_单元测试报告.docx`。

## 开发人员
- 成员 B：[张载沅]（24281245）
- 小组 10：刘一栋、张载沅、于心媛、孙浩然
- 课程：软件综合实训